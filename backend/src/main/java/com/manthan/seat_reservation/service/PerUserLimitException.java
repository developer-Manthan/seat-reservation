package com.manthan.seat_reservation.service;

/** The request would take the user over the show's per-user seat limit: 409 per-user-limit. */
public class PerUserLimitException extends RuntimeException {

	public PerUserLimitException(int requested, int limit) {
		super("Booking " + requested + " more seat(s) would exceed the limit of " + limit + " seats per user for this show");
	}

}
