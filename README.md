# Seat Reservation

A small service for booking numbered seats at a show. Many people can try to book the same seat at the same moment, and only one of them gets it. A person can't book more seats than the show allows, and sending the same request twice never books twice.

It is a Spring Boot API on MySQL, with a one-page UI on top.

- Deployed app: https://seat-booking.up.railway.app
- Logs of the deployed app in Grafana: https://grafana-production-47f8.up.railway.app/d/adc6rmr/new-dashboard?from=now-1h&to=now&timezone=browser&refresh=5s

## Burst test

`burst.sh` fires 20,000 reservation requests at one new show with 200 seats, all at the same moment, and checks the result: one winner per hot seat, no 5xx, seat counts that always add up, safe retries, the per-user limit, and that nobody can cancel someone else's booking. It ends with `RESULT: PASSED` or `RESULT: FAILED` and the list of failed checks.

You need a Linux, macOS or WSL shell with Python 3. Nothing has to be installed.

**Against the local app**

1. Start the app and wait until http://localhost:8080 opens:

   ```bash
   docker compose up --build -d
   ```

2. Run the burst from the repository folder:

   ```bash
   bash burst.sh
   ```

3. Read the last line. Locally the script also checks the database at the end, so run it from a shell where `docker compose` works.

**Against the deployed app**

1. Get the deployed admin token from the table in [Local and deployed](#local-and-deployed).

2. Open the [Grafana logs](https://grafana-production-47f8.up.railway.app/d/adc6rmr/new-dashboard?from=now-1h&to=now&timezone=browser&refresh=5s) in a browser to watch the requests arrive. The Grafana username and password are in [Local and deployed](#local-and-deployed).

3. Run the burst:

   ```bash
   BASE_URL=https://seat-booking.up.railway.app ADMIN_TOKEN=<prod-admin-token> bash burst.sh
   ```

   The database check is skipped here, because the script cannot reach the deployed database.

**Options**

- A smaller burst: `REQUESTS=8000 bash burst.sh`
- At most 1000 requests in flight instead of all at once: `bash burst.sh --concurrency 1000`

A full burst takes a few minutes and creates about 16,000 users and one show, which stay in the database. Don't use the app while it runs: other requests move the counters and the metrics check will fail.

## Run it

To run it in local you only need Docker. A deployed copy is also running, see [Local and deployed](#local-and-deployed) below.

```bash
docker compose up --build
```

Then open http://localhost:8080. The first start takes a minute while the image builds and the database comes up.

The page lets you pick a user (or add one), see the shows, book seats and cancel them. To create a show, open "Create a show" on the shows page and enter the admin token. Locally that is `dev-only-admin-token`.

To stop it: `docker compose down`. Your data stays. Add `-v` to wipe the database as well.

## Local and deployed

The same app runs in two places:

| | Address | Admin token |
|---|---|---|
| Local | http://localhost:8080 | `dev-only-admin-token` |
| Deployed (Railway) | https://seat-booking.up.railway.app | `8906a8a456b875dac0e256be0b6d39696c19e270733173f7201dcd633308a547` |

Wherever a command in this README says `<prod-admin-token>`, use the deployed admin token from the table above.

Grafana, for the logs of the deployed app:

| | |
|---|---|
| Address | https://grafana-production-47f8.up.railway.app/d/adc6rmr/new-dashboard?from=now-1h&to=now&timezone=browser&refresh=5s |
| Username | `admin` |
| Password | `admin` |

Everything in this README works against either one. To try something on the deployed app, change `http://localhost:8080` to `https://seat-booking.up.railway.app` in the command and, for admin calls, use the deployed admin token. The UI is at the same address in a browser.

User tokens are separate too: a token from the local app does not work on the deployed one. Get a new one from `/auth/token` there.

## Use the API directly

Create a show (admin):

Local:
```bash
curl -X POST http://localhost:8080/shows \
  -H "Authorization: Bearer dev-only-admin-token" -H "Content-Type: application/json" \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4"],"price_paise":25000}'
```
Prod:
```bash
curl -X POST https://seat-booking.up.railway.app/shows \
  -H "Authorization: Bearer <prod-admin-token>" -H "Content-Type: application/json" \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4"],"price_paise":25000}'
```


Get a token for a user. The user id is any UUID, and the user is created if it is new:

Local:
```bash
curl -X POST http://localhost:8080/auth/token -H "Content-Type: application/json" \
  -d '{"user_id":"11111111-2222-3333-4444-555555555555"}'
```
Prod:
```bash
curl -X POST https://seat-booking.up.railway.app/auth/token -H "Content-Type: application/json" \
  -d '{"user_id":"11111111-2222-3333-4444-555555555555"}'
```

Book two seats with that token. Use the show `id` from the first response in place of `1`:

Local:
```bash
curl -X POST http://localhost:8080/shows/1/reserve \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"seats":["A1","A2"],"idempotency_key":"order-1"}'
```
Prod:
```bash
curl -X POST https://seat-booking.up.railway.app/shows/1/reserve \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"seats":["A1","A2"],"idempotency_key":"order-1"}'
```

`mode` is optional. Leave it out and the request is `all_or_nothing`: both seats or none. Add `"mode":"best_effort"` to book whichever of the seats are free:

```bash
curl -X POST http://localhost:8080/shows/1/reserve   -H "Authorization: Bearer <token>" -H "Content-Type: application/json"   -d '{"seats":["A3","A4"],"idempotency_key":"order-2","mode":"best_effort"}'
```

Cancel it, using the `reservation_id` from the booking:

Local:
```bash
curl -X POST http://localhost:8080/reservations/<reservation_id>/cancel -H "Authorization: Bearer <token>"
```
Prod:
```bash
curl -X POST https://seat-booking.up.railway.app/reservations/<reservation_id>/cancel -H "Authorization: Bearer <token>"
```

## Endpoints

| Method and path | Who | What it does |
|---|---|---|
| `POST /auth/token` | anyone | Returns a token for a user id (a UUID). Valid for 24 hours. |
| `GET /users` | anyone | Lists users, for the UI's user picker. |
| `POST /shows` | admin | Creates a show with its seats and price. |
| `GET /shows` | any token | Lists shows with the number of seats still available. |
| `GET /shows/{id}` | any token | One show: every seat with its status, and the counts. |
| `POST /shows/{id}/reserve` | user | Books seats. |
| `GET /reservations` | user | Your own reservations. Add `?show_id=` for one show. |
| `POST /reservations/{id}/cancel` | user, owner only | Cancels a reservation and frees its seats. |
| `GET /healthz`, `GET /readyz` | anyone | Liveness, and readiness (checks the database). |
| `GET /actuator/prometheus` | admin | Metrics in Prometheus text format: bookings confirmed, declined by reason, cancelled, and seats available per show. See [Logs and metrics](#logs-and-metrics). |

Send the token as `Authorization: Bearer <token>`.

## How booking behaves

**Two modes.** Add `"mode"` to the reserve request:
- `all_or_nothing` (the default) books every seat you asked for, or none.
- `best_effort` books whichever of them are free and tells you which were not, in `unavailable_seats`.

**The idempotency key is required.** Send it as `idempotency_key` in the body or as an `Idempotency-Key` header. If you send the exact same request again with the same key, you get the same reservation back and nothing is booked a second time. If you reuse a key for a different request, you get a 409.

**Limits.** Each show has a per-user seat limit (4 unless you set `per_user_limit`). Cancelling gives those seats back to your limit.

**Money** is whole paise. `price_paise: 25000` is ₹250.00. A price like `12.5` is rejected.

**Seat labels** are not case-sensitive. `a1` is stored and returned as `A1`.

**Admin tokens can't book or cancel.** The admin token has no user behind it, so it gets a 403 on those two endpoints. Use a user token.

**When something is refused**, the answer looks like this, and the same `trace_id` is in the `X-Trace-Id` response header and in the logs:

```json
{"error":"seat-taken","message":"Seat(s) not available: A2","trace_id":"3f6c..."}
```

| Status | `error` | Meaning |
|---|---|---|
| 400 | `bad-request` | Something in the request is wrong. The message names the field. |
| 401 | `unauthorized` | No token, or a bad or expired one. |
| 403 | `forbidden` | The token is not allowed to do this. |
| 404 | `not-found` | No such show or reservation (or the reservation is not yours). |
| 409 | `seat-taken` | A seat was already booked. |
| 409 | `per-user-limit` | This would take you over the show's limit. |
| 409 | `idempotency-conflict` | The key was already used for a different request. |
| 409 | `already-cancelled` | You already cancelled this reservation. |
| 409 | `show-name-taken` | A show with this name exists. |
| 429 | `too-many-requests` | The service is busy. Wait for the `Retry-After` seconds and try again. |

A request that is refused leaves nothing behind: no reservation and no stored key.

## Settings

Everything has a working default for local use, so you don't need a `.env` file. To change something, put only that variable in a `.env` file next to `docker-compose.yml`.

| Variable | Default | What it is |
|---|---|---|
| `PORT` | `8080` | Port the app listens on. |
| `TOKEN_SECRET` | dev value | Secret used to sign user tokens. |
| `ADMIN_TOKEN` | `dev-only-admin-token` | The admin token. |
| `TOKEN_TTL_SECONDS` | `86400` | How long a user token lasts. |
| `APP_DB_HOST`, `APP_DB_PORT`, `APP_DB_NAME`, `APP_DB_USER`, `APP_DB_PASSWORD` | the local MySQL | Which database the app uses. |
| `APP_DB_JDBC_PARAMS` | no TLS, batched inserts | Extra JDBC options. Keep `rewriteBatchedStatements=true` if you change it. |
| `APP_DB_POOL_SIZE` | `25` | Database connections. |
| `TOMCAT_MAX_CONNECTIONS` | `25000` | Connections the web server keeps open at once. |
| `APP_RESERVATION_DEFAULT_MODE` | `all_or_nothing` | Mode used when a request doesn't say. |
| `APP_SHOWS_MAX_SEATS` | `5000` | Most seats one show can have. |
| `RESERVATION_MAX_ATTEMPTS` | `5` | Tries before answering 429 on a lock conflict. |
| `LOG_DIR` | `./logs` | Where the daily log file goes. |
| `LOG_LEVEL` | `INFO` | Log level. |
| `LOKI_URL` | not set | Address of Grafana Loki. When set, logs are also sent there. |

`.env.example` lists them all. Don't copy it as it is: its `APP_DB_HOST=localhost` line is for running the app outside Docker and would break the Docker setup.

**For a real deployment**, set `TOKEN_SECRET` and `ADMIN_TOKEN`. Without that the app refuses to start if either is missing or still a dev value.

## Logs and metrics

Each log line is one JSON object. Lines that belong to a request carry its `trace_id` and the `user_id`, so you can follow one request from start to finish.

- In Docker: `docker compose logs -f app`
- As a file: `./logs/ddMMyyyy.log`, one file per day (the day changes at midnight India time).

**Grafana (optional).** To also send the logs to Grafana Loki, tell the app where Loki is:

```
LOKI_URL=http://loki.railway.internal:3100
```

That is the only setting. Without it nothing is sent. In Grafana, the query `{app="seat-reservation"}` shows the lines. If Loki is down, the app keeps running and only those lines are lost.

The deployed app sends its logs there. They are on this dashboard (the login is in [Local and deployed](#local-and-deployed)): https://grafana-production-47f8.up.railway.app/d/adc6rmr/new-dashboard?from=now-1h&to=now&timezone=browser&refresh=5s

The local app does not send logs to Grafana unless you set `LOKI_URL`. Read its logs with the two ways above.

**Metrics** are at `/actuator/prometheus`, in the text format Prometheus reads. It needs the admin token, without it the answer is 401:

```bash
curl http://localhost:8080/actuator/prometheus -H "Authorization: Bearer dev-only-admin-token"
```

| Metric | Type | What it counts |
|---|---|---|
| `reservations_confirmed_total` | counter | Bookings that succeeded. |
| `reservations_declined_total{reason="..."}` | counter | Requests that did not make a new booking, by reason: `seat-taken`, `per-user-limit`, `idempotent-replay` (a repeat that got its original reservation back), `idempotency-conflict`. |
| `reservations_partial_total` | counter | `best_effort` bookings that got fewer seats than asked for. |
| `reservations_cancelled_total` | counter | Cancellations. |
| `reservations_retries_total` | counter | Times a booking or cancel was tried again after a lock conflict. |
| `requests_throttled_total` | counter | 429 answers. |
| `seats_available{show_id="..."}` | gauge | Seats still free in each show. |

The counters start at zero each time the app starts. `seats_available` is read from the database every time the endpoint is called, so it always matches `GET /shows/{id}`. The load test checks both: the gauge against the show, and each counter against the answers it received.

## Tests

The tests need the database from Docker, on port 3307:

```bash
docker compose up -d mysql
cd backend && ./mvnw test
```

They use their own database (`seat_reservation_test`), so they don't touch your data. The suite includes tests that fire up to 1000 requests at one seat at the same instant.

**Load test** against the running app (Python 3, nothing to install):

```bash
python3 scripts/load_test.py
```

It runs five scenarios, prints response times and status counts, and ends by checking the metrics against the answers it got and that the database is consistent. That last step calls `docker compose`, so run it from a shell where Docker works. Use `--users 500` for more load. It leaves its `load-...` shows in the database.

Against the deployed app, give it the address and the deployed admin token, and skip the database check (it can only reach the local database):

```bash
python3 scripts/load_test.py --base-url https://seat-booking.up.railway.app --admin-token <prod-admin-token> --no-reconcile
```

Don't use the deployed app while it runs: other requests move the counters and the metrics check will fail.

For the large run, 20,000 requests at a single show with 200 seats, use `bash burst.sh`. See [Burst test](#burst-test) at the top. It is a short wrapper around `python3 scripts/load_test.py --only storm`.

**Check the database by hand** at any time. Every line should end in 0:

```bash
docker compose exec -T mysql mysql -useat -pdev-only-password seat_reservation < scripts/reconcile.sql
```

## Good to know

- This is a demo when it comes to sign-in. There are no passwords: anyone can get a token for any user id, and the UI lists the users. Don't put real data in it.
- The lists (`/users`, `/shows`, `/reservations`) return the newest 200 and are not paged.
- There are no delete endpoints. Shows and users stay until you wipe the database.
