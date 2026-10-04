package com.manthan.seat_reservation.service;

import java.util.List;

/** Seats could not be booked (taken, unknown, or none won in best_effort): 409 seat-taken. */
public class SeatTakenException extends RuntimeException {

	private final List<String> seats;

	public SeatTakenException(List<String> seats) {
		super("Seat(s) not available: " + String.join(", ", seats));
		this.seats = List.copyOf(seats);
	}

	public List<String> getSeats() {
		return seats;
	}

}
