package com.manthan.seat_reservation.repository;

/**
 * Connectivity probe used by the readiness endpoint.
 */
public interface HealthRepository {

	/**
	 * Runs {@code SELECT 1}. Throws if the database is unreachable.
	 */
	void ping();

}
