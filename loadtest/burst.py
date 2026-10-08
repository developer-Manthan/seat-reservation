"""
The burst, in one small file: 20,000 reservation requests at one new show with 200 seats, all sent in the same instant.

It runs next to the app (inside the same private network), so nothing but the app is being tested:

    BASE_URL=http://localhost:8080 ADMIN_TOKEN=dev-only-admin-token REQUESTS=2000 python3 loadtest/burst.py

How it works, top to bottom:
    1. set up     create the show, the users and a few bookings to attack
    2. plan       decide who asks for what (five groups, see plan_burst)
    3. fire       open one connection per request, hold them all, then send every request together
    4. check      the six acceptance points, each printed as PASS or FAIL

Standard library only. The exit code is 0 when every check passed.
"""
import asyncio
import http.client
import io
import itertools
import json
import os
import socket
import ssl
import sys
import threading
import time
import uuid
from collections import Counter, namedtuple
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "dev-only-admin-token")
REQUESTS = int(os.environ.get("REQUESTS", "20000"))

TARGET = urlsplit(BASE_URL)
HOST = TARGET.hostname
PORT = TARGET.port or (443 if TARGET.scheme == "https" else 80)
TLS = ssl.create_default_context() if TARGET.scheme == "https" else None

SEATS = [f"S{number}" for number in range(1, 201)]
HOT_SEATS = SEATS[:5]
PER_USER_LIMIT = 4
OPEN_AT_A_TIME = 500
OPEN_TIMEOUT = 10       # seconds to wait for one connection to open
FIRST_LOCAL_PORT, LAST_LOCAL_PORT = 10000, 65000      # the local ports the burst's connections use

# How many users each small group has. Everyone else is in the "hot" group.
RETRY_USERS, RETRY_COPIES = 50, 4       # the same request sent 4 times
CONFLICT_USERS = 25                     # one key used for two different requests
LIMIT_USERS, LIMIT_TRIES = 5, 10        # 10 bookings each, the limit is 4
VICTIMS, ATTACKERS = 20, 100            # attackers try to cancel a victim's booking
SPOOFERS = 5                            # book while naming another user in the body

Request = namedtuple("Request", "group user method path body")


# ---------------------------------------------------------------- ordinary calls (set-up and reads)

_thread = threading.local()


def call(method, path, token=None, body=None):
    """One ordinary request on this thread's connection. Returns (status, parsed JSON)."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body) if body is not None else None
    for attempt in (1, 2):
        if getattr(_thread, "connection", None) is None:
            factory = http.client.HTTPSConnection if TLS else http.client.HTTPConnection
            _thread.connection = factory(HOST, PORT, timeout=60)
        try:
            _thread.connection.request(method, path, body=data, headers=headers)
            response = _thread.connection.getresponse()
            return response.status, as_json(response.read())
        except (http.client.HTTPException, OSError):
            _thread.connection = None       # the server closed it: open a new one and try once more
            if attempt == 2:
                raise


def wait_until_ready():
    while True:
        try:
            if call("GET", "/readyz")[0] == 200:
                return
        except (http.client.HTTPException, OSError):
            pass
        print("   waiting for the app ...", flush=True)
        time.sleep(3)


def as_json(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return None


def must(expected_status, answer):
    status, body = answer
    if status != expected_status:
        sys.exit(f"Set-up failed: expected {expected_status}, got {status} {body}")
    return body


def new_users(count):
    """Creates users and returns {user id: token}."""
    ids = [str(uuid.uuid4()) for _ in range(count)]
    with ThreadPoolExecutor(max_workers=32) as pool:
        tokens = pool.map(lambda user: must(200, call("POST", "/auth/token", body={"user_id": user}))["token"], ids)
        return dict(zip(ids, tokens))


def book(show, token, seats, key):
    return call("POST", f"/shows/{show}/reserve", token, {"seats": seats, "idempotency_key": key})


# ---------------------------------------------------------------- the plan


def plan_burst(show, users, victims):
    """
    Returns the requests of the burst. users is {group name: [user ids]}, victims is {user id: reservation id}.

    hot        everyone asks for one of the 5 hot seats        -> one winner per seat, the rest 409 seat-taken
    retry      the same request (same key) sent 4 times        -> 4 x 201 with one and the same reservation
    conflict   one key used for two different seats            -> one 201, one 409 idempotency-conflict
    limit      10 single-seat bookings by one user, limit 4    -> 4 x 201, 6 x 409 per-user-limit
    attack     cancel a reservation that belongs to a victim   -> 404
    spoof      book while naming a victim as user_id in body   -> the booking belongs to the token's user
    """
    reserve = f"/shows/{show}/reserve"
    free = iter(SEATS[len(HOT_SEATS) + len(victims):])      # the victims already hold the seats after the hot ones
    victim_ids = list(victims)
    requests = []

    for number, user in enumerate(users["hot"]):
        seat = HOT_SEATS[number % len(HOT_SEATS)]
        requests.append(Request("hot", user, "POST", reserve, {"seats": [seat], "idempotency_key": "hot"}))

    for user in users["retry"]:
        body = {"seats": [next(free)], "idempotency_key": "retry"}
        requests += [Request("retry", user, "POST", reserve, body)] * RETRY_COPIES

    for user in users["conflict"]:
        for _ in range(2):
            requests.append(Request("conflict", user, "POST", reserve, {"seats": [next(free)], "idempotency_key": "same-key"}))

    for user in users["limit"]:
        for attempt in range(LIMIT_TRIES):
            requests.append(Request("limit", user, "POST", reserve, {"seats": [next(free)], "idempotency_key": f"try-{attempt}"}))

    for number, user in enumerate(users["attack"]):
        victim = victim_ids[number % len(victim_ids)]
        requests.append(Request("attack", user, "POST", f"/reservations/{victims[victim]}/cancel", None))

    for number, user in enumerate(users["spoof"]):
        body = {"seats": [next(free)], "idempotency_key": "spoof", "user_id": victim_ids[number]}
        requests.append(Request("spoof", user, "POST", reserve, body))

    return requests


# ---------------------------------------------------------------- the burst


def as_bytes(request, token):
    body = json.dumps(request.body).encode() if request.body is not None else b""
    head = (f"{request.method} {request.path} HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nAuthorization: Bearer {token}\r\n"
            f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n")
    return head.encode() + body


class _AlreadyRead:
    """Lets http.client parse an answer whose bytes were already read from the connection."""

    def __init__(self, raw):
        self._file = io.BytesIO(raw)

    def makefile(self, *_args, **_kwargs):
        return self._file


def parse_answer(raw):
    response = http.client.HTTPResponse(_AlreadyRead(raw))
    response.begin()
    return response.status, as_json(response.read())


async def fire(requests, tokens):
    """
    Opens one connection per request, holds them all open, then sends every request in the same instant.
    Returns one (status, body) per request, in order. Status 0 means no answer, and body says why.

    Every connection from this machine to the app needs its own local port. Left to itself the system hands them
    out from a small range (about 6,000 on Railway), and connection 6,001 fails with "Cannot assign requested
    address". So each connection is given a local port from our own, much larger range.
    """
    # Look the name up once, not once per connection. IPv4 first: Docker forwards localhost over IPv4.
    found = socket.getaddrinfo(HOST, PORT, type=socket.SOCK_STREAM)
    family, _, _, _, target = sorted(found, key=lambda entry: entry[0] != socket.AF_INET)[0]
    any_local_address = "::" if family == socket.AF_INET6 else "0.0.0.0"
    local_ports = itertools.cycle(range(FIRST_LOCAL_PORT, LAST_LOCAL_PORT))

    payloads = [as_bytes(request, tokens[request.user]) for request in requests]
    connections = [None] * len(requests)
    why_not = {}        # index -> the reason its connection could not be opened
    gate = asyncio.Semaphore(OPEN_AT_A_TIME)

    async def open_one(index):
        for _ in range(3):      # a port may be taken by something else: try the next one
            async with gate:
                try:
                    connections[index] = await asyncio.wait_for(asyncio.open_connection(
                        target[0], PORT, ssl=TLS, server_hostname=HOST if TLS else None,
                        local_addr=(any_local_address, next(local_ports))), OPEN_TIMEOUT)
                    return
                except (OSError, asyncio.TimeoutError) as error:
                    why_not[index] = f"could not connect: {getattr(error, 'strerror', None) or type(error).__name__}"

    async def read_one(index):
        if connections[index] is None:
            return 0, why_not.get(index, "could not connect")
        reader, writer = connections[index]
        try:
            await writer.drain()
            raw = await asyncio.wait_for(reader.read(), 600)
            return parse_answer(raw) if raw else (0, "closed without an answer")
        except Exception as error:
            return 0, type(error).__name__
        finally:
            writer.close()

    async def report_progress():        # a sign of life every 5 seconds while the connections are being opened
        while True:
            await asyncio.sleep(5)
            open_now = sum(1 for connection in connections if connection is not None)
            reasons = dict(Counter(why_not.values()))
            print(f"   ... {open_now} open after {time.perf_counter() - started:.0f}s" + (f", failed attempts: {reasons}" if reasons else ""),
                  flush=True)

    started = time.perf_counter()
    progress = asyncio.create_task(report_progress())
    await asyncio.gather(*[open_one(index) for index in range(len(requests))])
    progress.cancel()
    opened = [index for index, connection in enumerate(connections) if connection is not None]
    print(f"   {len(opened)} of {len(requests)} connections opened in {time.perf_counter() - started:.1f}s and held open", flush=True)

    first = time.time()
    for index in opened:
        connections[index][1].write(payloads[index])
    last = time.time()
    print(f"   first request sent at {clock(first)} UTC, last at {clock(last)} UTC: {1000 * (last - first):.0f} ms apart", flush=True)

    answers = await asyncio.gather(*[read_one(index) for index in range(len(requests))])
    print(f"   last answer arrived {time.time() - first:.1f}s after the first request was sent")
    return answers


def clock(since_epoch):
    return time.strftime("%H:%M:%S", time.gmtime(since_epoch)) + f".{int(since_epoch % 1 * 1000):03d}"


def allow_open_files(needed):
    """One open connection is one open file. Raise this process's limit as far as the system allows."""
    try:
        import resource
    except ImportError:         # Windows has no such limit
        return
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    wanted = needed if hard == resource.RLIM_INFINITY else min(needed, hard)
    if wanted > soft:
        resource.setrlimit(resource.RLIMIT_NOFILE, (wanted, hard))
    if max(wanted, soft) < needed:
        print(f"   WARNING: this system allows {max(wanted, soft)} open files, the burst needs {needed}", flush=True)


def describe_local_ports():
    """Prints the range the system would hand out by itself, to make a port shortage visible in the log."""
    try:
        with open("/proc/sys/net/ipv4/ip_local_port_range") as setting:
            low, high = setting.read().split()
        print(f"   the system's own local ports: {low} to {high} ({int(high) - int(low) + 1}). "
              f"The burst uses {FIRST_LOCAL_PORT} to {LAST_LOCAL_PORT - 1} instead", flush=True)
    except (OSError, ValueError):
        pass


# ---------------------------------------------------------------- the checks

failed = []


def check(name, passed, detail=""):
    print(f"   [{'PASS' if passed else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not passed else ""))
    if not passed:
        failed.append(name)


def counts_add_up(view):
    counts = view["counts"]
    return counts["available"] + counts["held"] + counts["confirmed"] == view["total_seats"] == len(view["seats"])


def error_of(answer):
    _, body = answer
    return body.get("error") if isinstance(body, dict) else body


def check_everything(show, users, victims, tokens, requests, answers, samples):
    by_group = {}
    for request, answer in zip(requests, answers):
        by_group.setdefault(request.group, []).append((request, answer))

    def by_user(group):
        grouped = {}
        for request, answer in by_group[group]:
            grouped.setdefault(request.user, []).append(answer)
        return grouped.values()

    statuses = Counter(status for status, _ in answers)
    print(f"   answers: {dict(sorted(statuses.items()))}   (0 means no answer)")

    # 2. No server errors, and nobody left without an answer.
    check("zero 5xx", not any(status >= 500 for status in statuses), str(statuses))
    reasons = Counter(body for status, body in answers if status == 0)
    check("every request got an answer", statuses[0] == 0, str(dict(reasons)))

    # 1. Each hot seat has exactly one winner, everyone else is told the seat is taken.
    for seat in HOT_SEATS:
        asked = [answer for request, answer in by_group["hot"] if request.body["seats"] == [seat]]
        winners = sum(1 for status, _ in asked if status == 201)
        refused = sum(1 for answer in asked if answer[0] == 409 and error_of(answer) == "seat-taken")
        check(f"hot seat {seat}: one winner among {len(asked)} users, everyone else 409 seat-taken",
              winners == 1 and refused == len(asked) - 1, f"{winners} winners, {refused} refused")

    # 4. Retries are safe, and a key cannot be reused for something else.
    same = [all(status == 201 for status, _ in copies) and len({body["reservation_id"] for _, body in copies}) == 1
            for copies in by_user("retry")]
    check(f"retry: {RETRY_COPIES} copies of one request give one and the same reservation", all(same), f"{same.count(False)} users differ")
    split = [sorted(status for status, _ in pair) == [201, 409]
             and all(error_of(answer) == "idempotency-conflict" for answer in pair if answer[0] == 409)
             for pair in by_user("conflict")]
    check("conflict: one key, two different requests: one booked, the other 409 idempotency-conflict", all(split),
          f"{split.count(False)} users differ")

    # 5. The per-user limit holds under concurrency.
    within = [sum(1 for status, _ in tries if status == 201) == PER_USER_LIMIT
              and sum(1 for answer in tries if answer[0] == 409 and error_of(answer) == "per-user-limit") == LIMIT_TRIES - PER_USER_LIMIT
              for tries in by_user("limit")]
    check(f"limit: {LIMIT_TRIES} bookings by one user give exactly {PER_USER_LIMIT}, the rest 409 per-user-limit", all(within),
          f"{within.count(False)} users differ")

    # 6. Identity comes from the token: nobody cancels or books for somebody else.
    check("attack: every attempt to cancel another user's reservation is 404",
          all(status == 404 for _, (status, _) in by_group["attack"]))
    def owned(user):
        return len(must(200, call("GET", f"/reservations?show_id={show}", tokens[user])))

    check("attack: every victim still holds the reservation", all(owned(victim) == 1 for victim in victims))
    check("spoof: a booking that names another user in the body belongs to the user of the token",
          all(status == 201 for _, (status, _) in by_group["spoof"]) and all(owned(user) == 1 for user in users["spoof"]))

    # 3. The seat counts add up, during the burst and after it.
    view = must(200, call("GET", f"/shows/{show}", ADMIN_TOKEN))
    check(f"available + held + confirmed == total_seats in all {len(samples)} reads during the burst", all(samples))
    check("available + held + confirmed == total_seats after the burst", counts_add_up(view))
    expected = len(HOT_SEATS) + RETRY_USERS + CONFLICT_USERS + LIMIT_USERS * PER_USER_LIMIT + len(victims) + SPOOFERS
    check(f"exactly {expected} seats are booked, as the answers say", view["counts"]["confirmed"] == expected,
          f"the show has {view['counts']['confirmed']}")


# ---------------------------------------------------------------- main


def main():
    special = RETRY_USERS * RETRY_COPIES + CONFLICT_USERS * 2 + LIMIT_USERS * LIMIT_TRIES + ATTACKERS + SPOOFERS
    hot_users = REQUESTS - special
    if hot_users < len(HOT_SEATS):
        sys.exit(f"REQUESTS must be at least {special + len(HOT_SEATS)}")

    print(f"Burst of {REQUESTS} requests against {BASE_URL}", flush=True)
    wait_until_ready()

    print("1. set up", flush=True)
    show = must(201, call("POST", "/shows", ADMIN_TOKEN, {"name": f"burst-{uuid.uuid4().hex[:8]}", "seats": SEATS,
                                                           "price_paise": 10000, "per_user_limit": PER_USER_LIMIT}))["id"]
    sizes = {"hot": hot_users, "retry": RETRY_USERS, "conflict": CONFLICT_USERS, "limit": LIMIT_USERS,
             "attack": ATTACKERS, "spoof": SPOOFERS, "victim": VICTIMS}
    tokens = new_users(sum(sizes.values()))
    ids = iter(tokens)
    users = {group: [next(ids) for _ in range(size)] for group, size in sizes.items()}
    victim_seats = SEATS[len(HOT_SEATS):len(HOT_SEATS) + VICTIMS]
    victims = {user: must(201, book(show, tokens[user], [seat], "victim"))["reservation_id"]
               for user, seat in zip(users["victim"], victim_seats)}
    print(f"   show {show} with {len(SEATS)} seats, {len(tokens)} users, {len(victims)} bookings to attack", flush=True)

    print("2. plan", flush=True)
    requests = plan_burst(show, users, victims)
    print(f"   {dict(Counter(request.group for request in requests))}", flush=True)

    print("3. fire", flush=True)
    allow_open_files(len(requests) + 100)
    describe_local_ports()
    samples, burst_over = [], threading.Event()

    def watch():        # reads the show every 2 seconds for as long as the burst runs
        while not burst_over.wait(2):
            try:
                status, view = call("GET", f"/shows/{show}", ADMIN_TOKEN)
            except (http.client.HTTPException, OSError):
                continue
            if status == 200:
                samples.append(counts_add_up(view))

    watcher = threading.Thread(target=watch, daemon=True)
    watcher.start()
    answers = asyncio.run(fire(requests, tokens))
    burst_over.set()
    watcher.join(timeout=60)

    print("4. check", flush=True)
    check_everything(show, users, victims, tokens, requests, answers, samples)

    print(f"\nRESULT: {'FAILED, ' + str(len(failed)) + ' check(s)' if failed else 'PASSED, every check held.'}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
