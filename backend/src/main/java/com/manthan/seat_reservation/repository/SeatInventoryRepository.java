package com.manthan.seat_reservation.repository;

import java.util.List;

/** Creates the seat inventory of a show. Needs an enclosing transaction. */
public interface SeatInventoryRepository {

	/** Inserts every label as an available seat of the show, in chunks. */
	void insertSeats(long showId, List<String> seatLabels);

}
