package com.manthan.seat_reservation.service;

/** No such reservation, or it belongs to someone else (the two are deliberately indistinguishable): 404. */
public class ReservationNotFoundException extends RuntimeException {

	public ReservationNotFoundException() {
		super("No such reservation");
	}

}
