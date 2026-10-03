package com.manthan.seat_reservation.domain;

/**
 * Lowercase constants on purpose: {@code @Enumerated(EnumType.STRING)} stores the constant name, and the
 * MySQL ENUM values are lowercase. {@code held} stays unused until a hold-with-expiry model exists.
 */
public enum SeatStatus {
	available, held, confirmed
}
