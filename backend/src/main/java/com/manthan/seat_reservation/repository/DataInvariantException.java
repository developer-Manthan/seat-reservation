package com.manthan.seat_reservation.repository;

/**
 * An invariant the data layer relies on was broken, for example an INSERT IGNORE that affected 0 rows although
 * the row does not exist (a swallowed foreign key or data error). This is a bug, never a decline or a replay.
 * A dedicated type so Spring's repository exception translation leaves it alone.
 */
public class DataInvariantException extends RuntimeException {

	public DataInvariantException(String message) {
		super(message);
	}

}
