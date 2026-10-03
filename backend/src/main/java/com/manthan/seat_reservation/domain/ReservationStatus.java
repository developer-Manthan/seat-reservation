package com.manthan.seat_reservation.domain;

/**
 * Lowercase constants to match the MySQL ENUM values (see {@link SeatStatus}).
 * {@code pending} exists only inside the reserve transaction and is never committed.
 */
public enum ReservationStatus {
	pending, confirmed, cancelled
}
