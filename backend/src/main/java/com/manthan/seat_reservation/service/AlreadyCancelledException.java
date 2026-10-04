package com.manthan.seat_reservation.service;

/** The caller owns this reservation and it is already cancelled: 409 already-cancelled. */
public class AlreadyCancelledException extends RuntimeException {

	public AlreadyCancelledException() {
		super("This reservation is already cancelled");
	}

}
