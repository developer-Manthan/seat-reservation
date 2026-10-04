package com.manthan.seat_reservation.service;

/** Lock conflicts that survived every retry, or an exhausted connection pool: 429 with a Retry-After header. */
public class ServiceBusyException extends RuntimeException {

	public ServiceBusyException(String message) {
		super(message);
	}

}
