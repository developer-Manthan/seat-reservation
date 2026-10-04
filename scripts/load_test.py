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
    python scripts/load_test.py --base-url https://your-app.up.railway.app --admin-token "$ADMIN_TOKEN" --no-reconcile

Each run creates its own shows (named load-...), so it can be repeated. The shows stay in the database afterwards,
because the API has no delete endpoint. The exit code is 0 only if every check passed.
"""
import argparse
import json
import os
import random
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

SCENARIOS = ["hot-seat", "same-key", "overlap", "user-limit", "cancel-rebook"]


class Api:
    def __init__(self, base_url, admin_token, timeout):
        self.base_url = base_url.rstrip("/")
        self.admin_token = admin_token
        self.timeout = timeout

    def call(self, method, path, token=None, body=None, trace_id=None):
        """Returns (status, parsed JSON or text, seconds). Never raises for an HTTP error status."""
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if trace_id:
            headers["X-Trace-Id"] = trace_id
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        except Exception as error:  # connection refused, timeout, ...
            return 0, {"error": "client-error", "message": str(error)}, time.perf_counter() - started
        elapsed = time.perf_counter() - started
        text = raw.decode("utf-8", "replace")
        try:
            return status, json.loads(text), elapsed
        except ValueError:
            return status, text, elapsed

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
    parser.add_argument("--only", nargs="+", choices=SCENARIOS, help="run only these scenarios")
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
               "user-limit": scenario_user_limit, "cancel-rebook": scenario_cancel_rebook}
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
