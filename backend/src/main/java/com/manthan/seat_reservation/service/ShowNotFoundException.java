package com.manthan.seat_reservation.service;

/** No show with this id: 404. */
public class ShowNotFoundException extends RuntimeException {

	public ShowNotFoundException(long id) {
		super("No show with id " + id);
	}

}
