#!/usr/bin/env python3
"""
Load test for the seat reservation service. Standard library only, nothing to install.

It fires many requests at the same instant and checks the promises the service makes:
no seat is sold twice, the per-user limit holds, a retried request never books twice, and nothing answers 5xx.
At the end it compares the metrics with GET /shows/{id} and runs scripts/reconcile.sql against the database.

Run it against the local Docker setup (docker compose up -d):

    python scripts/load_test.py

Useful options:

    python scripts/load_test.py --users 500              # more concurrent users per scenario
    python scripts/load_test.py --only hot-seat overlap  # run some scenarios only
    python scripts/load_test.py --only storm             # the big one: 20,000 requests at one show with 200 seats
    python scripts/load_test.py --base-url https://your-app.up.railway.app --admin-token "$ADMIN_TOKEN" --no-reconcile

Each run creates its own shows (named load-...), so it can be repeated. The shows stay in the database afterwards,
because the API has no delete endpoint. The exit code is 0 only if every check passed.
"""
import argparse
import http.client
import json
import os
import random
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

# The five quick scenarios run by default. "storm" is the big one and runs only when asked for (--only storm).
SCENARIOS = ["hot-seat", "same-key", "overlap", "user-limit", "cancel-rebook"]
STORM = "storm"


class Api:
    def __init__(self, base_url, admin_token, timeout):
        self.base_url = base_url.rstrip("/")
        self.admin_token = admin_token
        self.timeout = timeout
        parts = urllib.parse.urlsplit(self.base_url)
        self._https = parts.scheme == "https"
        self._host = parts.hostname
        self._port = parts.port or (443 if self._https else 80)
        self._prefix = parts.path.rstrip("/")
        self._local = threading.local()
        self.seen = Counter()
        self._seen_lock = threading.Lock()

    def _connection(self, fresh=False):
        """One keep-alive connection per thread, so thousands of requests do not open thousands of sockets."""
        connection = getattr(self._local, "connection", None)
        if fresh and connection is not None:
            connection.close()
            connection = None
        if connection is None:
            factory = http.client.HTTPSConnection if self._https else http.client.HTTPConnection
            connection = factory(self._host, self._port, timeout=self.timeout)
            self._local.connection = connection
        return connection

    def observe(self, method, path, status, answer):
        """Remembers the outcome of every reserve and cancel request, to compare with the server's counters at the end."""
        kind = "reserve" if path.endswith("/reserve") else "cancel" if path.endswith("/cancel") else None
        if method != "POST" or kind is None:
            if status == 429:
                with self._seen_lock:
                    self.seen["429"] += 1
            return
        error = answer.get("error") if isinstance(answer, dict) else None
        with self._seen_lock:
            if status == 0:
                self.seen["unanswered"] += 1
            elif status == 429:
                self.seen["429"] += 1
            elif status in (200, 201):
                self.seen[f"{kind} {status}"] += 1
            else:
                self.seen[f"{kind} {status} {error}"] += 1

    def call(self, method, path, token=None, body=None, trace_id=None):
        """Returns (status, parsed JSON or text, seconds). Never raises. Status 0 means the request itself failed."""
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if trace_id:
            headers["X-Trace-Id"] = trace_id
        data = json.dumps(body).encode() if body is not None else None
        started = time.perf_counter()
        # The server closes idle keep-alive connections. If a reused connection turns out to be dead before any
        # answer arrived, try once more on a new one.
        for attempt in (1, 2):
            reused = getattr(self._local, "connection", None) is not None
            connection = self._connection()
            try:
                connection.request(method, self._prefix + path, body=data, headers=headers)
                response = connection.getresponse()
                status, raw = response.status, response.read()
                break
            except (http.client.HTTPException, OSError) as error:
                self._connection(fresh=True)
                if attempt == 2 or not reused:
                    self.observe(method, path, 0, None)
                    return 0, {"error": "client-error", "message": f"{type(error).__name__}: {error}"}, time.perf_counter() - started
        elapsed = time.perf_counter() - started
        text = raw.decode("utf-8", "replace")
        try:
            answer = json.loads(text)
        except ValueError:
            answer = text
        self.observe(method, path, status, answer)
        return status, answer, elapsed

    def token_for(self, user_id):
        status, body, _ = self.call("POST", "/auth/token", body={"user_id": user_id})
        if status != 200:
            raise SystemExit(f"Could not get a token: {status} {body}")
        return body["token"]

    def create_show(self, name, seats, per_user_limit=4, price_paise=10000):
        status, body, _ = self.call("POST", "/shows", token=self.admin_token,
                                    body={"name": name, "seats": seats, "price_paise": price_paise, "per_user_limit": per_user_limit})
        if status != 201:
            raise SystemExit(f"Could not create the show '{name}': {status} {body}")
        return body["id"]

    def show(self, show_id):
        status, body, _ = self.call("GET", f"/shows/{show_id}", token=self.admin_token)
        if status != 200:
            raise SystemExit(f"Could not read show {show_id}: {status} {body}")
        return body

    def reservations(self, token, show_id):
        status, body, _ = self.call("GET", f"/reservations?show_id={show_id}", token=token)
        if status != 200:
            raise SystemExit(f"Could not read reservations: {status} {body}")
        return body

    def metrics(self):
        """The Prometheus text as {series: value}, or None if the endpoint cannot be read."""
        status, body, _ = self.call("GET", "/actuator/prometheus", token=self.admin_token)
        if status != 200 or not isinstance(body, str):
            return None
        values = {}
        for line in body.splitlines():
            if line and not line.startswith("#"):
                name, _, value = line.rpartition(" ")
                try:
                    values[name] = float(value)
                except ValueError:
                    pass
        return values


def _raise_open_file_limit(needed):
    """Every open connection is an open file. Raise this process's limit if the system allows it."""
    try:
        import resource
    except ImportError:          # Windows has no such limit to raise
        return
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    if soft >= needed:
        return
    target = needed if hard == resource.RLIM_INFINITY else min(needed, hard)
    try:
        resource.setrlimit(resource.RLIMIT_NOFILE, (target, hard))
    except (ValueError, OSError):
        target = soft
    if target < needed:
        raise SystemExit(f"This run needs about {needed} open connections but the system allows {target}. "
                         f"Raise it first with: ulimit -n {needed}   (or pass a smaller --concurrency)")


def _parse_response(raw):
    """Splits a raw HTTP/1.1 response read to the end of the connection into (status, body bytes)."""
    head, _, rest = raw.partition(b"\r\n\r\n")
    lines = head.split(b"\r\n")
    status = int(lines[0].split()[1])
    headers = {}
    for line in lines[1:]:
        key, _, value = line.partition(b":")
        headers[key.strip().lower()] = value.strip().lower()
    if b"chunked" in headers.get(b"transfer-encoding", b""):
        body, position = b"", 0
        while True:
            end = rest.find(b"\r\n", position)
            if end < 0:
                break
            size = int(rest[position:end].split(b";")[0] or b"0", 16)
            if size == 0:
                break
            body += rest[end + 2:end + 2 + size]
            position = end + 2 + size + 2
        return status, body
    return status, rest


def fire(api, jobs, concurrency, timeout):
    """
    Sends every job (method, path, token, body) and returns (status, answer, seconds) for each, in order.
    concurrency 0 means all at once: every request is started in the same instant, each on its own connection, and is
    sent the moment its connection opens. A number above 0 keeps at most that many requests in flight instead.
    Also reports how many requests were in flight together at the peak. Standard library only (asyncio).
    """
    import asyncio
    import socket
    import ssl

    at_once = concurrency <= 0
    _raise_open_file_limit((len(jobs) if at_once else concurrency) + 200)
    context = ssl.create_default_context() if api._https else None
    # Look the address up once, not 20,000 times, and prefer IPv4 (Docker forwards localhost over IPv4).
    infos = socket.getaddrinfo(api._host, api._port, type=socket.SOCK_STREAM)
    address = sorted(infos, key=lambda info: info[0] != socket.AF_INET)[0][4][0]
    results = [None] * len(jobs)
    stats = {"in_flight": 0, "peak_in_flight": 0, "connected": 0, "last_sent_after": 0.0}

    def request_bytes(method, path, token, body):
        data = json.dumps(body).encode() if body is not None else b""
        head = (f"{method} {api._prefix}{path} HTTP/1.1\r\nHost: {api._host}\r\nContent-Type: application/json\r\n"
                f"Authorization: Bearer {token}\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n")
        return head.encode() + data

    async def run():
        limit = asyncio.Semaphore(concurrency) if not at_once else None
        # A few thousand connection attempts at a time, so the listen queue of the server is not flooded with
        # attempts it would only make the client repeat. Every request is still started at the same moment.
        opening = asyncio.Semaphore(2000)
        began = time.perf_counter()

        async def one(index, job):
            payload = request_bytes(*job)
            writer = None
            counted = False
            started = time.perf_counter()
            try:
                if limit:
                    await limit.acquire()
                    started = time.perf_counter()
                async with opening:
                    reader, writer = await asyncio.wait_for(
                        asyncio.open_connection(address, api._port, ssl=context, server_hostname=api._host if context else None), 120)
                stats["connected"] += 1
                writer.write(payload)
                await writer.drain()
                stats["in_flight"] += 1
                counted = True
                stats["peak_in_flight"] = max(stats["peak_in_flight"], stats["in_flight"])
                stats["last_sent_after"] = max(stats["last_sent_after"], time.perf_counter() - began)
                raw = await asyncio.wait_for(reader.read(), timeout)
                status, body = _parse_response(raw)
                text = body.decode("utf-8", "replace")
                try:
                    answer = json.loads(text)
                except ValueError:
                    answer = text
                results[index] = (status, answer, time.perf_counter() - started)
                api.observe(job[0], job[1], status, answer)
            except Exception as error:
                results[index] = (0, {"error": "client-error", "message": f"{type(error).__name__}: {error}"}, time.perf_counter() - started)
                api.observe(job[0], job[1], 0, None)
            finally:
                if counted:
                    stats["in_flight"] -= 1
                if limit:
                    limit.release()
                if writer is not None:
                    writer.close()

        stats["fired_at"] = time.perf_counter()
        await asyncio.gather(*[one(i, job) for i, job in enumerate(jobs)])

    asyncio.run(run())
    return results, stats


def run_together(tasks):
    """Runs every task on its own thread, all released at the same instant. Returns the results in task order."""
    barrier = threading.Barrier(len(tasks))

    def gated(task):
        barrier.wait()
        return task()

    with ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        return list(pool.map(gated, tasks))


class Report:
    def __init__(self):
        self.failed = []

    def scenario(self, name, results, seconds):
        statuses = Counter(status for status, _, _ in results)
        latencies = sorted(elapsed * 1000 for _, _, elapsed in results)

        def pct(p):
            return latencies[min(len(latencies) - 1, int(len(latencies) * p))]

        print(f"\n== {name}")
        print(f"   requests {len(results)}   statuses {dict(sorted(statuses.items()))}   "
              f"{len(results) / seconds:.0f} req/s   total {seconds:.2f}s")
        print(f"   latency ms: p50 {statistics.median(latencies):.0f}   p95 {pct(0.95):.0f}   p99 {pct(0.99):.0f}   max {latencies[-1]:.0f}")
        server_errors = {s: n for s, n in statuses.items() if s >= 500 or s == 0}
        self.check(name, "no 5xx and no failed connections", not server_errors, f"got {server_errors}")
        if statuses.get(429):
            print(f"   note: {statuses[429]} request(s) answered 429 (retry later). Allowed under load, but worth a look.")
        return statuses

    def check(self, scenario, what, passed, detail=""):
        print(f"   [{'PASS' if passed else 'FAIL'}] {what}" + ("" if passed else f"  ({detail})"))
        if not passed:
            self.failed.append(f"{scenario}: {what} {detail}")


def errors_of(results, status=409):
    return Counter(body.get("error") if isinstance(body, dict) else "?" for s, body, _ in results if s == status)


def check_show(api, report, name, show_id, expected_confirmed):
    """The show view must add up, and must agree with what the responses said was booked."""
    view = api.show(show_id)
    counts = view["counts"]
    report.check(name, "available + held + confirmed == total_seats",
                 counts["available"] + counts["held"] + counts["confirmed"] == view["total_seats"], str(counts))
    report.check(name, f"confirmed seats in the show view == seats the responses booked ({expected_confirmed})",
                 counts["confirmed"] == expected_confirmed, f"show view says {counts['confirmed']}")
    return view


def scenario_hot_seat(api, report, args, tag):
    """Many users, one seat: exactly one may win."""
    for mode in ("all_or_nothing", "best_effort"):
        name = f"hot seat, {args.users} users, {mode}"
        show = api.create_show(f"load-hot-{mode}-{tag}", ["HOT"])
        tokens = [api.token_for(str(uuid.uuid4())) for _ in range(args.users)]
        tasks = [lambda t=t, i=i: api.call("POST", f"/shows/{show}/reserve", token=t, trace_id=f"load-hot-{tag}-{i}",
                                           body={"seats": ["HOT"], "idempotency_key": "k", "mode": mode})
                 for i, t in enumerate(tokens)]
        started = time.perf_counter()
        results = run_together(tasks)
        statuses = report.scenario(name, results, time.perf_counter() - started)
        report.check(name, "exactly one 201", statuses.get(201) == 1, f"got {statuses.get(201, 0)}")
        report.check(name, "every other answer is 409 seat-taken",
                     errors_of(results).get("seat-taken", 0) == args.users - 1, str(dict(errors_of(results))))
        check_show(api, report, name, show, 1)


def scenario_same_key(api, report, args, tag):
    """The same request sent many times at once (a client retrying): exactly one booking, every answer a 201."""
    copies = max(10, args.users // 3)
    name = f"same key, {copies} copies at once"
    show = api.create_show(f"load-samekey-{tag}", ["A1", "A2"])
    token = api.token_for(str(uuid.uuid4()))
    tasks = [lambda i=i: api.call("POST", f"/shows/{show}/reserve", token=token, trace_id=f"load-samekey-{tag}-{i}",
                                  body={"seats": ["A1", "A2"], "idempotency_key": "same-key"}) for i in range(copies)]
    started = time.perf_counter()
    results = run_together(tasks)
    statuses = report.scenario(name, results, time.perf_counter() - started)
    ids = {body.get("reservation_id") for s, body, _ in results if s == 201}
    report.check(name, "every copy answered 201", statuses.get(201) == copies, str(dict(statuses)))
    report.check(name, "all copies returned the same reservation", len(ids) == 1, f"{len(ids)} different ids")
    check_show(api, report, name, show, 2)


def scenario_overlap(api, report, args, tag):
    """Many users each wanting a random few seats out of a small hall: seats overlap heavily."""
    rng = random.Random(42)
    seats = [f"S{i}" for i in range(1, args.seats + 1)]
    for mode in ("all_or_nothing", "best_effort"):
        name = f"overlapping seats, {args.users} users x 3 seats out of {args.seats}, {mode}"
        show = api.create_show(f"load-overlap-{mode}-{tag}", seats)
        wanted = [rng.sample(seats, 3) for _ in range(args.users)]
        tokens = [api.token_for(str(uuid.uuid4())) for _ in range(args.users)]
        tasks = [lambda t=t, w=w, i=i: api.call("POST", f"/shows/{show}/reserve", token=t, trace_id=f"load-overlap-{tag}-{i}",
                                                body={"seats": w, "idempotency_key": "k", "mode": mode})
                 for i, (t, w) in enumerate(zip(tokens, wanted))]
        started = time.perf_counter()
        results = run_together(tasks)
        report.scenario(name, results, time.perf_counter() - started)
        booked = [seat for s, body, _ in results if s == 201 for seat in body["seats"]]
        report.check(name, "no seat appears in two bookings", len(booked) == len(set(booked)),
                     f"{len(booked) - len(set(booked))} seat(s) sold twice")
        if mode == "all_or_nothing":
            partial = [body for s, body, _ in results if s == 201 and len(body["seats"]) != 3]
            report.check(name, "every booking has all 3 seats or none", not partial, f"{len(partial)} partial booking(s)")
        view = check_show(api, report, name, show, len(booked))
        confirmed = {seat["seat_label"] for seat in view["seats"] if seat["status"] == "confirmed"}
        report.check(name, "the confirmed seats are exactly the seats the responses reported", confirmed == set(booked))


def scenario_user_limit(api, report, args, tag):
    """One user firing many requests at once must never get more seats than the limit."""
    limit, parallel = 4, max(20, args.users // 3)
    name = f"per-user limit {limit}, one user, {parallel} requests at once"
    show = api.create_show(f"load-limit-{tag}", [f"S{i}" for i in range(1, parallel + 1)], per_user_limit=limit)
    token = api.token_for(str(uuid.uuid4()))
    tasks = [lambda i=i: api.call("POST", f"/shows/{show}/reserve", token=token, trace_id=f"load-limit-{tag}-{i}",
                                  body={"seats": [f"S{i}"], "idempotency_key": f"key-{i}"}) for i in range(1, parallel + 1)]
    started = time.perf_counter()
    results = run_together(tasks)
    statuses = report.scenario(name, results, time.perf_counter() - started)
    report.check(name, f"exactly {limit} bookings succeeded", statuses.get(201) == limit, f"got {statuses.get(201, 0)}")
    report.check(name, "every other answer is 409 per-user-limit",
                 errors_of(results).get("per-user-limit", 0) == parallel - limit, str(dict(errors_of(results))))
    check_show(api, report, name, show, limit)


def scenario_cancel_rebook(api, report, args, tag):
    """Holders cancel while new users try to take the same seats. Each seat ends with one owner or none."""
    pairs = max(10, args.users // 4)
    name = f"cancel racing reserve, {pairs} seats"
    seats = [f"S{i}" for i in range(1, pairs + 1)]
    show = api.create_show(f"load-cancel-{tag}", seats)
    holders = [api.token_for(str(uuid.uuid4())) for _ in range(pairs)]
    bidders = [api.token_for(str(uuid.uuid4())) for _ in range(pairs)]
    reservations = []
    for token, seat in zip(holders, seats):
        status, body, _ = api.call("POST", f"/shows/{show}/reserve", token=token, body={"seats": [seat], "idempotency_key": "hold"})
        if status != 201:
            raise SystemExit(f"Setup failed, could not hold {seat}: {status} {body}")
        reservations.append(body["reservation_id"])
    tasks = []
    for i, (holder, bidder, seat, reservation) in enumerate(zip(holders, bidders, seats, reservations)):
        tasks.append(lambda h=holder, r=reservation, i=i: api.call("POST", f"/reservations/{r}/cancel", token=h, trace_id=f"load-cancel-{tag}-{i}"))
        tasks.append(lambda b=bidder, s=seat, i=i: api.call("POST", f"/shows/{show}/reserve", token=b, trace_id=f"load-rebook-{tag}-{i}",
                                                            body={"seats": [s], "idempotency_key": "bid"}))
    started = time.perf_counter()
    results = run_together(tasks)
    statuses = report.scenario(name, results, time.perf_counter() - started)
    cancels, bids = results[0::2], results[1::2]
    report.check(name, "every holder's cancel answered 200", all(s == 200 for s, _, _ in cancels), str(Counter(s for s, _, _ in cancels)))
    report.check(name, "every bid answered 201 or 409 seat-taken",
                 all(s == 201 or (s == 409 and b.get("error") == "seat-taken") for s, b, _ in bids), str(dict(statuses)))
    rebooked = sum(1 for s, _, _ in bids if s == 201)
    print(f"   {rebooked} seat(s) went to the new user, {pairs - rebooked} ended free")
    check_show(api, report, name, show, rebooked)


def scenario_storm(api, report, args, tag):
    """
    One fresh show with 200 seats and limit 4, hit by about --requests requests at once, all mixed together:
      hot      many users each want one of a handful of hot seats (some send the same request three times)
      retry    a user sends the same request (same key) ten times
      conflict a user sends two different requests under the same key
      limit    a user fires ten bookings at once on a show that allows four
      spoof    a user books while claiming to be someone else in the body
      attack   a user tries to cancel a reservation that belongs to someone else
    While it runs, a checker keeps reading the show and adding up the seat counts.
    """
    seat_count, per_user_limit = 200, 4
    seats = [f"S{i}" for i in range(1, seat_count + 1)]
    name = f"storm: {args.requests} requests, one show, {seat_count} seats, " + (
        "all at once" if args.concurrency <= 0 else f"{args.concurrency} in flight at a time")
    show = api.create_show(f"load-storm-{tag}", seats, per_user_limit=per_user_limit)
    rng = random.Random(7)
    cursor = [0]

    def take(n):
        chunk = seats[cursor[0]:cursor[0] + n]
        cursor[0] += n
        return chunk

    hot_seats = take(args.hot_seats)
    users = []          # user ids, the token is fetched before the storm
    plan = []           # (group, user, method, path, body)

    def new_user():
        users.append(str(uuid.uuid4()))
        return users[-1]

    def reserve(group, user, wanted, key, extra=None):
        body = {"seats": wanted, "idempotency_key": key}
        body.update(extra or {})
        plan.append((group, user, "POST", f"/shows/{show}/reserve", body))

    # retry: 40 users, each sends the same request 10 times
    retry_users = {new_user(): seat for seat in take(40)}
    for user, seat in retry_users.items():
        for _ in range(10):
            reserve("retry", user, [seat], "retry")

    # conflict: 20 users, each sends seat a five times and seat b five times under ONE key
    conflict_users = {new_user(): take(2) for _ in range(20)}
    for user, (a, b) in conflict_users.items():
        for _ in range(5):
            reserve("conflict", user, [a], "one-key")
            reserve("conflict", user, [b], "one-key")

    # limit: 10 users, each fires 10 single-seat bookings (different keys) on a limit of 4
    limit_users = {new_user(): take(10) for _ in range(10)}
    for user, own in limit_users.items():
        for i, seat in enumerate(own):
            reserve("limit", user, [seat], f"limit-{i}")

    # spoof and attack: 5 victims already hold a seat. 5 attackers book while naming the victim in the body, and
    # try to cancel the reservation of the victim 20 times.
    victims = {new_user(): seat for seat in take(5)}
    attackers = {new_user(): seat for seat in take(5)}

    # hot: everything else. Every tenth user sends the same request three times.
    hot_users = defaultdict(list)
    remaining = args.requests - len(plan) - len(attackers) * 21
    i = 0
    while remaining > 0:
        seat = hot_seats[i % len(hot_seats)]
        user = new_user()
        hot_users[seat].append(user)
        copies = min(remaining, 3 if i % 10 == 0 else 1)
        for _ in range(copies):
            reserve("hot", user, [seat], "hot")
        remaining -= copies
        i += 1

    print(f"\n== {name}")
    print(f"   setting up {len(users)} users ...", flush=True)
    with ThreadPoolExecutor(max_workers=64) as pool:
        tokens = dict(zip(users, pool.map(api.token_for, users)))

    victim_reservations = {}
    for victim, seat in victims.items():
        status, body, _ = api.call("POST", f"/shows/{show}/reserve", token=tokens[victim], body={"seats": [seat], "idempotency_key": "victim"})
        if status != 201:
            raise SystemExit(f"Setup failed, the victim could not book {seat}: {status} {body}")
        victim_reservations[victim] = body["reservation_id"]
    for (attacker, seat), (victim, reservation) in zip(attackers.items(), victim_reservations.items()):
        reserve("spoof", attacker, [seat], "spoof", {"user_id": victim})
        for _ in range(20):
            plan.append(("attack", attacker, "POST", f"/reservations/{reservation}/cancel", {"user_id": victim}))

    rng.shuffle(plan)

    # The checker: reads the show the whole time and adds up the counts.
    stop = threading.Event()
    samples, broken = [0], []

    def watch():
        while not stop.is_set():
            status, view, _ = api.call("GET", f"/shows/{show}", token=api.admin_token)
            if status == 200:
                counts = view["counts"]
                samples[0] += 1
                if counts["available"] + counts["held"] + counts["confirmed"] != view["total_seats"] or len(view["seats"]) != seat_count:
                    broken.append(counts)
            stop.wait(0.2)

    watcher = threading.Thread(target=watch, daemon=True)
    watcher.start()

    how = "all at once" if args.concurrency <= 0 else f"{args.concurrency} in flight at a time"
    print(f"   firing {len(plan)} requests, {how} ...", flush=True)
    jobs = [(method, path, tokens[user], body) for (_, user, method, path, body) in plan]
    results, stats = fire(api, jobs, args.concurrency, max(args.timeout, 600))
    seconds = time.perf_counter() - stats["fired_at"]
    stop.set()
    watcher.join(timeout=30)
    print(f"   {stats['connected']} of {len(plan)} connections opened, the last request was sent {stats['last_sent_after']:.1f}s after the first, "
          f"and at the peak {stats['peak_in_flight']} requests were in flight together")
    failures = Counter(a.get("message", "?").split(":")[0] for s_, a, _ in results if s_ == 0 and isinstance(a, dict))
    if failures:
        print(f"   requests that got no answer, by cause: {dict(failures.most_common(5))}")

    statuses = report.scenario(name, results, seconds)
    by_group = defaultdict(Counter)
    by_user = defaultdict(list)
    for (group, user, _, _, body), (status, answer, _) in zip(plan, results):
        by_group[group][status] += 1
        by_user[(group, user)].append((status, answer, body))
    for group in ("hot", "retry", "conflict", "limit", "spoof", "attack"):
        print(f"   {group:9s} {sum(by_group[group].values()):6d} requests  {dict(sorted(by_group[group].items()))}")

    def error_of(answer):
        return answer.get("error") if isinstance(answer, dict) else None

    # 2. zero 5xx is checked by report.scenario above. A 429 is not a 5xx, but the brief wants 409s, so flag it.
    report.check(name, "no 429 (every refusal is a clean 409 or 404)", not statuses.get(429), f"{statuses.get(429, 0)} request(s) got 429")

    # 1. each hot seat: exactly one winner, everyone else 409 seat-taken
    seat_owners = defaultdict(set)
    for (status, answer, _) in results:
        if status == 201 and isinstance(answer, dict):
            for seat in answer.get("seats", []):
                seat_owners[seat].add(answer["reservation_id"])
    report.check(name, "no seat is held by two reservations", all(len(ids) == 1 for ids in seat_owners.values()),
                 str({s: len(i) for s, i in seat_owners.items() if len(i) > 1}))
    for seat in hot_seats:
        winners = [u for u in hot_users[seat] if any(s == 201 for s, _, _ in by_user[("hot", u)])]
        losers_clean = all(s == 409 and error_of(a) == "seat-taken"
                           for u in hot_users[seat] if u not in winners for s, a, _ in by_user[("hot", u)])
        winner_consistent = all(s == 201 for u in winners for s, _, _ in by_user[("hot", u)])
        report.check(name, f"hot seat {seat}: exactly one of {len(hot_users[seat])} users won it",
                     len(winners) == 1 and len(seat_owners[seat]) == 1, f"{len(winners)} winner(s), {len(seat_owners[seat])} reservation(s)")
        report.check(name, f"hot seat {seat}: every other user got 409 seat-taken", losers_clean and winner_consistent)

    # 4. idempotency
    retry_ok = all(all(s == 201 for s, _, _ in by_user[("retry", u)]) and len({a["reservation_id"] for _, a, _ in by_user[("retry", u)]}) == 1
                   for u in retry_users)
    report.check(name, "retry: every copy of the same request got 201 with one and the same reservation", retry_ok)
    conflict_ok, conflict_refused = True, 0
    for user in conflict_users:
        answers = by_user[("conflict", user)]
        ids = {a["reservation_id"] for s, a, _ in answers if s == 201}
        refused = [a for s, a, _ in answers if s != 201]
        conflict_refused += len(refused)
        mine = [r for r in api.reservations(tokens[user], show) if r["status"] == "confirmed"]
        if len(ids) != 1 or len(refused) != 5 or any(error_of(a) != "idempotency-conflict" for a in refused) \
                or len(mine) != 1 or len(mine[0]["seats"]) != 1:
            conflict_ok = False
    report.check(name, f"conflict: one key, two different requests: one reservation, the other request refused ({conflict_refused} x 409 idempotency-conflict)",
                 conflict_ok)

    # 5. per-user limit
    limit_ok = True
    for user in limit_users:
        answers = by_user[("limit", user)]
        booked = sum(1 for s, _, _ in answers if s == 201)
        refused_ok = all(s == 409 and error_of(a) == "per-user-limit" for s, a, _ in answers if s != 201)
        held = sum(len(r["seats"]) for r in api.reservations(tokens[user], show) if r["status"] == "confirmed")
        if booked != per_user_limit or held != per_user_limit or not refused_ok:
            limit_ok = False
    report.check(name, f"limit: each user who fired 10 bookings holds exactly {per_user_limit}, the rest got 409 per-user-limit", limit_ok)

    # 6. identity comes from the token
    spoof_ok, attack_ok = True, True
    for (attacker, seat), (victim, reservation) in zip(attackers.items(), victim_reservations.items()):
        attacker_seats = {s for r in api.reservations(tokens[attacker], show) if r["status"] == "confirmed" for s in r["seats"]}
        victim_now = api.reservations(tokens[victim], show)
        victim_seats = {s for r in victim_now if r["status"] == "confirmed" for s in r["seats"]}
        if seat not in attacker_seats or seat in victim_seats:
            spoof_ok = False
        if victim_seats != {victims[victim]} or any(r["reservation_id"] == reservation and r["status"] != "confirmed" for r in victim_now):
            attack_ok = False
        if any(s != 404 for s, _, _ in by_user[("attack", attacker)]):
            attack_ok = False
    report.check(name, "spoof: a booking that names another user in the body belongs to the user of the token", spoof_ok)
    report.check(name, "attack: nobody could cancel a reservation of another user (all 404), the victims still hold their seats", attack_ok)

    # 3. the invariant, during and after
    report.check(name, f"available + held + confirmed == total_seats in all {samples[0]} reads taken during the storm",
                 samples[0] > 0 and not broken, f"{len(broken)} bad read(s), {samples[0]} read(s)")
    expected = len(seat_owners) + len(victims)
    view = check_show(api, report, name, show, expected)
    confirmed = {seat["seat_label"] for seat in view["seats"] if seat["status"] == "confirmed"}
    report.check(name, "the confirmed seats are exactly the seats the responses reported", confirmed == set(seat_owners) | set(victims.values()))


def compare_metrics(api, report, before, show_checks):
    after = api.metrics()
    print("\n== metrics (from /actuator/prometheus)")
    if before is None or after is None:
        print("   skipped: the metrics endpoint could not be read with this admin token")
        return
    for series in sorted(k for k in after if k.startswith(("reservations_", "requests_throttled"))):
        delta = after[series] - before.get(series, 0)
        if delta:
            print(f"   {series:60s} +{delta:.0f}")
    def grew(series):
        return int(after.get(series, 0) - before.get(series, 0))

    def declined(reason):
        return grew(f'reservations_declined_total{{reason="{reason}"}}')

    # The counters must have grown by exactly what this run saw in its own responses. A first booking and a replay
    # both answer 201, so those two counters are compared together. Other clients using the app during the run, or a
    # request that got no answer (the server may still have processed it), make this comparison meaningless.
    seen = api.seen
    if seen["unanswered"]:
        print(f"   counters not compared with the responses: {seen['unanswered']} request(s) got no answer")
    else:
        pairs = [
            ("confirmed + idempotent-replay", grew("reservations_confirmed_total") + declined("idempotent-replay"), "201 answers to reserve", seen["reserve 201"]),
            ("declined{seat-taken}", declined("seat-taken"), "409 seat-taken answers", seen["reserve 409 seat-taken"]),
            ("declined{per-user-limit}", declined("per-user-limit"), "409 per-user-limit answers", seen["reserve 409 per-user-limit"]),
            ("declined{idempotency-conflict}", declined("idempotency-conflict"), "409 idempotency-conflict answers", seen["reserve 409 idempotency-conflict"]),
            ("cancelled", grew("reservations_cancelled_total"), "200 answers to cancel", seen["cancel 200"]),
            ("requests_throttled", grew("requests_throttled_total"), "429 answers", seen["429"]),
        ]
        for metric, counted, what, observed in pairs:
            report.check("metrics", f"{metric} grew by {counted} == {observed} {what} seen by this run", counted == observed,
                         f"the counter grew by {counted}, this run saw {observed}")

    for show_id in show_checks:
        view = api.show(show_id)
        gauge = after.get(f'seats_available{{show_id="{show_id}"}}')
        # The gauge was read a moment before the show view, and nothing is running now, so they must agree.
        report.check("metrics", f"seats_available for show {show_id} == available in GET /shows/{show_id} ({view['counts']['available']})",
                     gauge == view["counts"]["available"], f"metric says {gauge}")


def reconcile(report, command, script):
    print("\n== reconciliation (scripts/reconcile.sql)")
    try:
        with open(script, "rb") as sql:
            done = subprocess.run(command, shell=True, stdin=sql, capture_output=True, timeout=120)
    except Exception as error:
        report.check("reconciliation", "the reconcile script ran", False, str(error))
        return
    if done.returncode != 0:
        report.check("reconciliation", "the reconcile script ran", False, done.stderr.decode("utf-8", "replace").strip()[-300:])
        return
    found = 0
    for line in done.stdout.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            found += 1
            report.check("reconciliation", f"{parts[0]} = 0", parts[1] == "0", f"{parts[1]} violation(s)")
    report.check("reconciliation", "all 8 checks were reported", found == 8, f"saw {found}")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Load test for the seat reservation service.")
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    parser.add_argument("--admin-token", default=os.environ.get("ADMIN_TOKEN", "dev-only-admin-token"))
    parser.add_argument("--users", type=int, default=300, help="concurrent users per scenario (default 300)")
    parser.add_argument("--seats", type=int, default=60, help="seats in the overlap scenario (default 60)")
    parser.add_argument("--timeout", type=float, default=90, help="seconds to wait for one response (default 90)")
    parser.add_argument("--only", nargs="+", choices=SCENARIOS + [STORM], help="run only these scenarios (storm runs only when named here)")
    parser.add_argument("--requests", type=int, default=20000, help="storm: total requests (default 20000)")
    parser.add_argument("--concurrency", type=int, default=0,
                        help="storm: 0 (default) sends every request at once, a number keeps at most that many in flight")
    parser.add_argument("--hot-seats", type=int, default=5, help="storm: number of hot seats (default 5)")
    parser.add_argument("--no-reconcile", action="store_true", help="skip the database reconciliation (for a remote app)")
    parser.add_argument("--reconcile-command", default="docker compose exec -T mysql mysql -N -useat -pdev-only-password seat_reservation",
                        help="command that reads SQL on stdin and runs it against the app's database")
    args = parser.parse_args()

    api = Api(args.base_url, args.admin_token, args.timeout)
    status, body, _ = api.call("GET", "/readyz")
    if status != 200:
        raise SystemExit(f"{args.base_url}/readyz answered {status} {body}. Is the app running?")

    tag = time.strftime("%H%M%S") + "-" + uuid.uuid4().hex[:4]
    report = Report()
    before = api.metrics()
    print(f"Load test against {args.base_url}, {args.users} users per scenario, run {tag}")
    runners = {"hot-seat": scenario_hot_seat, "same-key": scenario_same_key, "overlap": scenario_overlap,
               "user-limit": scenario_user_limit, "cancel-rebook": scenario_cancel_rebook, STORM: scenario_storm}
    started = time.perf_counter()
    for scenario in args.only or SCENARIOS:
        runners[scenario](api, report, args, tag)

    # A fresh show, half booked, to compare the live gauge with the show view.
    probe = api.create_show(f"load-probe-{tag}", ["A1", "A2", "A3", "A4"])
    api.call("POST", f"/shows/{probe}/reserve", token=api.token_for(str(uuid.uuid4())), body={"seats": ["A1", "A2"], "idempotency_key": "probe"})
    compare_metrics(api, report, before, [probe])
    if not args.no_reconcile:
        reconcile(report, args.reconcile_command, os.path.join(here, "reconcile.sql"))

    print(f"\nFinished in {time.perf_counter() - started:.1f}s.")
    if report.failed:
        print(f"RESULT: FAILED, {len(report.failed)} check(s):")
        for failure in report.failed:
            print("  - " + failure)
        sys.exit(1)
    print("RESULT: PASSED, every check held.")


if __name__ == "__main__":
    main()
