-- Runs once, when the MySQL data volume is first created (local compose only).
-- Integration tests use this separate schema so they never touch the dev data.
CREATE DATABASE IF NOT EXISTS seat_reservation_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON seat_reservation_test.* TO 'seat'@'%';
