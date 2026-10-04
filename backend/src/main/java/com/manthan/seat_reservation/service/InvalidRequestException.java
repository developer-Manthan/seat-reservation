package com.manthan.seat_reservation.service;

/** A request that is well formed but breaks a business rule (duplicate rows, too many seats, ...): 400. */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException(String message) {
		super(message);
	}

}
