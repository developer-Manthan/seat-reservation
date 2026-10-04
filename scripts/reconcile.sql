-- Reconciliation: cross-checks the reservation tables against each other.
-- Every statement returns (check_name, violations) and every violations value must be 0.
-- Run it after any load test or race test:
--   docker compose exec -T mysql mysql -useat -pdev-only-password seat_reservation < scripts/reconcile.sql
-- The race tests run this same file, so the script and the tests cannot drift apart.
-- One statement per check, each ending with a semicolon. Keep comments on their own lines.

-- 1. A pending reservation exists only inside a transaction. One after commit is a bug.
SELECT 'no_pending_reservations' AS check_name, COUNT(*) AS violations
FROM reservations WHERE status = 'pending';

-- 2. Per show, confirmed seats must equal reservation_seats rows (both change in the same transaction).
SELECT 'confirmed_seats_equal_reservation_seats' AS check_name, COUNT(*) AS violations FROM (
  SELECT s.id FROM shows s
  LEFT JOIN (SELECT show_id, COUNT(*) AS c FROM seats WHERE status = 'confirmed' GROUP BY show_id) a ON a.show_id = s.id
  LEFT JOIN (SELECT show_id, COUNT(*) AS c FROM reservation_seats GROUP BY show_id) b ON b.show_id = s.id
  WHERE COALESCE(a.c, 0) <> COALESCE(b.c, 0)
) mismatched_shows;

-- 3. Every reservation_seats row must belong to a confirmed reservation (cancel deletes them).
SELECT 'reservation_seats_belong_to_confirmed_reservations' AS check_name, COUNT(*) AS violations
FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id WHERE r.status <> 'confirmed';

-- 4. Every reservation_seats row must point at a seat that is confirmed.
SELECT 'reservation_seats_point_at_confirmed_seats' AS check_name, COUNT(*) AS violations
FROM reservation_seats rs JOIN seats s ON s.show_id = rs.show_id AND s.seat_label = rs.seat_label WHERE s.status <> 'confirmed';

-- 5. A confirmed reservation always holds at least one seat.
SELECT 'confirmed_reservations_hold_seats' AS check_name, COUNT(*) AS violations
FROM reservations r WHERE r.status = 'confirmed'
AND NOT EXISTS (SELECT 1 FROM reservation_seats rs WHERE rs.reservation_id = r.id);

-- 6. available + held + confirmed must equal total_seats, per show.
SELECT 'seat_counts_equal_total_seats' AS check_name, COUNT(*) AS violations
FROM shows s WHERE s.total_seats <> (SELECT COUNT(*) FROM seats WHERE show_id = s.id);

-- 7. Per user and show, the counter must equal the seats the user actually holds (in both directions).
SELECT 'counters_equal_booked_seats' AS check_name, COUNT(*) AS violations FROM (
  SELECT c.user_id, c.show_id FROM user_show_counts c
  LEFT JOIN (SELECT r.user_id, rs.show_id, COUNT(*) AS n FROM reservation_seats rs
             JOIN reservations r ON r.id = rs.reservation_id GROUP BY r.user_id, rs.show_id) b
    ON b.user_id = c.user_id AND b.show_id = c.show_id
  WHERE c.held_count <> COALESCE(b.n, 0)
  UNION ALL
  SELECT r.user_id, rs.show_id FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id
  LEFT JOIN user_show_counts c ON c.user_id = r.user_id AND c.show_id = rs.show_id
  WHERE c.user_id IS NULL GROUP BY r.user_id, rs.show_id
) mismatched_counters;

-- 8. Money: a confirmed reservation's amount is the show price times the seats it holds.
SELECT 'amount_equals_price_times_seats' AS check_name, COUNT(*) AS violations
FROM reservations r JOIN shows s ON s.id = r.show_id
WHERE r.status = 'confirmed'
AND r.amount_paise <> s.price_paise * (SELECT COUNT(*) FROM reservation_seats rs WHERE rs.reservation_id = r.id);
