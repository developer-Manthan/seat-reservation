package com.manthan.seat_reservation.domain;

/** How a multi-seat request treats seats that are not free. The names are the values accepted in the API. */
public enum ReservationMode {
	/** Books every requested seat or none. */
	all_or_nothing,
	/** Books whichever requested seats are free, and reports the rest. */
	best_effort
}
