package com.manthan.seat_reservation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.manthan.seat_reservation.domain.IdempotencyKey;
import com.manthan.seat_reservation.domain.Reservation;

/**
 * The data layer behind the reserve and cancel transactions. Services depend on this interface only, so the
 * implementation can be swapped. Booleans mean "this guarded update affected exactly one row". Every method
 * must be called inside a transaction.
 */
public interface SeatStore {

	/** Inserts the user if absent. Throws if the row is missing afterwards (a bug, not a decline). */
	void ensureUser(String userId);

	void insertPendingReservation(String reservationId, long showId, String userId);

	/**
	 * Native INSERT IGNORE. Returns true if inserted, false if the key already exists. If the insert affected
	 * 0 rows and the key is not there either, a swallowed error is hiding a bug and this throws.
	 */
	boolean insertIdempotencyKey(String userId, String idemKey, String requestHash, String reservationId);

	Optional<IdempotencyKey> findIdempotencyKey(String userId, String idemKey);

	Optional<Reservation> findReservation(String reservationId);

	/** Inserts the counter row if absent. Throws if the row is missing afterwards. */
	void ensureUserShowCount(String userId, long showId);

	/** Adds n to held_count only if the result stays within the limit. False means per-user-limit. */
	boolean tryIncrementUserCount(String userId, long showId, int n, int limit);

	/** Gives k back, only if held_count is at least k. */
	boolean decrementUserCount(String userId, long showId, int k);

	/**
	 * Which of these seats are available right now: a plain read, outside any transaction. It may only be used to
	 * refuse a request early, never to grant a seat. Granting is {@link #claimSeat} alone.
	 */
	Set<String> findAvailableSeatLabels(long showId, Collection<String> seatLabels);

	/** Guarded available to confirmed. True means this request won the seat. */
	boolean claimSeat(long showId, String seatLabel);

	/**
	 * Guarded available to confirmed for several seats in one statement. Returns how many were won. The caller must
	 * compare it with the number asked for and roll back when it is less.
	 */
	int claimSeats(long showId, Collection<String> seatLabels);

	/** Plain INSERT. A duplicate key throws DataIntegrityViolationException (the guard logic is broken). */
	void insertReservationSeat(long showId, String seatLabel, String reservationId);

	/** The same plain INSERT for several seats in one statement. Returns the rows inserted. */
	int insertReservationSeats(long showId, Collection<String> seatLabels, String reservationId);

	/** Guarded pending to confirmed with the real amount. */
	boolean confirmReservation(String reservationId, long amountPaise);

	/** Guarded confirmed to cancelled, owner only. */
	boolean cancelReservation(String reservationId, String userId);

	List<String> findSeatLabels(String reservationId);

	/** Guarded confirmed to available. */
	boolean releaseSeat(long showId, String seatLabel);

	int deleteReservationSeats(String reservationId);

}
