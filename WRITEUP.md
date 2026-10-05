# How it works, and why

This is the reasoning behind the seat reservation service: what the hard part is, how I handled it, what I got wrong along the way, and how I know it holds.

## The problem

Selling seats is easy until two people want the same one at the same moment. The obvious code reads the seat, sees it is free, and writes "taken". Two requests can both read "free" before either writes, and the seat is sold twice.

So the service has to keep three promises under any amount of concurrent traffic:

1. A seat is never sold twice.
2. Nobody gets more seats for a show than its limit.
3. Sending the same request again never creates a second booking.

And it should do this without falling over: a busy moment should produce clean "sorry, taken" answers, not errors.

## The one idea everything rests on

Never read a seat and then decide. Let the database decide in a single statement:

```sql
UPDATE seats SET status = 'confirmed'
WHERE show_id = ? AND seat_label = ? AND status = 'available'
```

The database runs this for one request at a time per row. Whoever gets there first changes one row. Everyone after them changes zero rows, because the seat is no longer `available`. The application only looks at that number: 1 means you won the seat, 0 means you didn't.

The same shape is used everywhere something can be contested: the per-user counter (`... WHERE held_count + ? <= limit`), confirming a reservation, cancelling one, releasing a seat. There is no place in the code that loads a seat, checks it and saves it.

## What happens when you book

Before any transaction, two plain reads: which of the requested seats are free right now, and whether this key was used before. A known key is answered straight away as a repeat. If the key is new and the request cannot succeed (a seat is taken), it is refused there and nothing is written. This read only ever refuses. It never gives anyone a seat: a seat that looks free is still decided by the guarded update below.

Then one database transaction, in this order:

1. **Create the reservation** as `pending`.
2. **Store the idempotency key**, pointing at that reservation. If the key already exists, this request is a repeat: roll back and return the original reservation.
3. **Check the limit** by adding the number of seats to the user's counter, only if the result stays within the limit.
4. **Claim the seats** with the guarded update above and record which reservation holds them. `all_or_nothing` does it in one statement for all seats and checks that every one of them changed. `best_effort` goes seat by seat in sorted order.
5. **Confirm** the reservation with the real amount (price × seats actually booked) and commit.

If anything is refused, the whole transaction rolls back. That is why a declined request leaves no trace: no reservation, no stored key, no change to the counter. It also means a client can safely retry a declined request with the same key later.

In `all_or_nothing` mode, one seat that can't be claimed stops the request and the others are given back. In `best_effort` mode it books what it can and hands back the unused part of the limit before committing.

The early read was added after profiling a burst of requests at a few hot seats. Almost all of them were refusals, and each refusal used to create a reservation, a key and a counter row only to undo them, which is what kept the database connections busy. Locally, a burst of 8,000 requests went from about 410 to about 740 answers per second with the early read and the single-statement claim together.

**Cancel** runs the same steps backwards, in the same order: mark the reservation cancelled (only if it is confirmed and yours), lower the counter, set each seat back to `available`, and remove the records of who held them. A freed seat only becomes bookable when that transaction commits.

## The choices behind it

**Seats are claimed in sorted order.** If one request takes A1 then A2 while another takes A2 then A1, each can end up waiting for the other forever. Always going in the same order makes that impossible. Cancel uses the same order.

**The user's counter row queues their own requests.** Once a request has updated a user's counter for a show, that row stays locked until it commits. A second request from the same user waits behind it, so the limit can't be passed by sending several requests at once.

**READ COMMITTED isolation.** A waiting request should see the seat as it is now, after the winner committed, not as it was when the request began.

**`pending` exists only inside a transaction.** The reservation has to be inserted before the things that point at it, but it should never be visible half-done. Other requests can't see uncommitted rows, and every failure rolls it back. A `pending` reservation in the database after a request finished would be a bug, and there is a check for exactly that. The value also stays in the schema on purpose: a future "hold a seat for ten minutes while you pay" feature can use `pending` and the unused seat status `held` without changing the tables.

**Two tables say a seat is taken, and they must agree.** `seats.status` is the inventory. `reservation_seats` says which reservation holds a seat, and its primary key allows one holder per seat. Both change in the same transaction. The second table is a backstop: if the first idea were ever broken, the database itself would refuse the duplicate.

**Retry happens outside the transaction.** A lock conflict can't be repaired from inside the transaction that hit it, so a small wrapper runs the whole attempt again in a fresh one, up to five times, with a short random wait. If it still can't get through, the answer is `429` with `Retry-After`, never a 500. A full connection pool gets the same answer.

**Money is whole paise** in a 64-bit integer. No floating point touches a price.

**The schema is owned by migrations** (Liquibase). The application never creates or changes tables. It only checks at startup that they match what it expects.

## How I know it holds

**Tests against a real MySQL**, about 220 of them. The ones that matter most release many requests at the same instant:

- 1000 users, one seat, in both modes: exactly one booking, 999 clean refusals.
- The same request sent 100 times at once: one reservation, and all 100 get the same answer.
- 200 users each wanting 3 random seats out of 60: no seat booked twice, and in `all_or_nothing` every booking has all its seats or none.
- One user sending 100 requests at once with a limit of 5: exactly 5 succeed.
- 200 rounds of someone cancelling while two others try to take the seat: it ends with one owner or none, every time.

None of these produce a single 5xx.

**A consistency check** (`scripts/reconcile.sql`) cross-examines the tables after any run: no `pending` left, confirmed seats equal recorded holders, every counter equals the seats that user holds, every amount equals price × seats. Each check has a test that breaks the data on purpose to prove the check notices. It has already earned its place: it flagged a seat I had edited by hand during an earlier experiment.

**A load test** (`scripts/load_test.py`) does the same against the running app. With 300 users per scenario it finished in about 30 seconds with every check passing and no 5xx.

## What it doesn't do

- **Sign-in is a placeholder.** Anyone can get a token for any user id. The tokens are properly signed and expire, but there is nothing behind them proving who you are.
- **Booking is immediate.** There is no "hold while you pay" step and no payment.
- **A refused request stores nothing**, so a retried refusal is processed again from scratch. That is deliberate, but it means the key only protects requests that succeeded.
- **Lists are not paged.** They return the newest 200.
- **`seats_available` is read from the database every time the metrics are requested.** It is always current, at the cost of one query per request.
- **Nothing can be deleted** through the API.

## If I kept going

1. **Hold with expiry**: seats go to `held` with a deadline, and are confirmed on payment or released by a timer. The schema already has room for it.
2. **Real sign-in**, and paging for the lists.
