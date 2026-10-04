package com.manthan.seat_reservation.service;

/** The idempotency key was already used for a different request: 409 idempotency-conflict. */
public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException() {
		super("This idempotency key was already used for a different request");
	}

}
