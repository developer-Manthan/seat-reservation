package com.manthan.seat_reservation.auth;

/** Valid token with the wrong role: 403. */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}

}
