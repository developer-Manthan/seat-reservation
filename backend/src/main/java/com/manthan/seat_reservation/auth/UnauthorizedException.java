package com.manthan.seat_reservation.auth;

/** Missing or invalid token: 401. */
public class UnauthorizedException extends RuntimeException {

	public UnauthorizedException(String message) {
		super(message);
	}

}
