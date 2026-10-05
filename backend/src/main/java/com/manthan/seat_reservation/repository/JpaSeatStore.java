package com.manthan.seat_reservation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Repository;

import com.manthan.seat_reservation.domain.IdempotencyKey;
import com.manthan.seat_reservation.domain.Reservation;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.domain.SeatStatus;

@Repository
class JpaSeatStore implements SeatStore {

	private final HotPathRepository repository;

	JpaSeatStore(HotPathRepository repository) {
		this.repository = repository;
	}

	@Override
	public void ensureUser(String userId) {
		if (repository.insertUserIfAbsent(userId) == 0 && repository.findUser(userId).isEmpty()) {
			throw new DataInvariantException("INSERT IGNORE into users affected 0 rows and the user is missing: " + userId);
		}
	}

	@Override
	public void insertPendingReservation(String reservationId, long showId, String userId) {
		repository.insertReservation(reservationId, showId, userId, ReservationStatus.pending.name());
	}

	@Override
	public boolean insertIdempotencyKey(String userId, String idemKey, String requestHash, String reservationId) {
		if (repository.insertIdempotencyKeyIfAbsent(userId, idemKey, requestHash, reservationId) == 1) {
			return true;
		}
		if (repository.findIdempotencyKey(userId, idemKey).isEmpty()) {
			throw new DataInvariantException(
					"INSERT IGNORE into idempotency_keys affected 0 rows and the key is missing, a swallowed error is hiding a bug");
		}
		return false;
	}

	@Override
	public Optional<IdempotencyKey> findIdempotencyKey(String userId, String idemKey) {
		return repository.findIdempotencyKey(userId, idemKey);
	}

	@Override
	public Optional<Reservation> findReservation(String reservationId) {
		return repository.findReservation(reservationId);
	}

	@Override
	public void ensureUserShowCount(String userId, long showId) {
		// A plain read first: a duplicate INSERT IGNORE takes a shared lock on the existing row, and two requests from
		// the same user that both hold it and then wait for the exclusive UPDATE lock would deadlock each other.
		// The INSERT IGNORE below stays the real guard for the first booking.
		if (repository.findUserShowCount(userId, showId).isPresent()) {
			return;
		}
		if (repository.insertUserShowCountIfAbsent(userId, showId) == 0
				&& repository.findUserShowCount(userId, showId).isEmpty()) {
			throw new DataInvariantException(
					"INSERT IGNORE into user_show_counts affected 0 rows and the row is missing: " + userId + "/" + showId);
		}
	}

	@Override
	public boolean tryIncrementUserCount(String userId, long showId, int n, int limit) {
		return repository.incrementHeldCount(userId, showId, n, limit) == 1;
	}

	@Override
	public boolean decrementUserCount(String userId, long showId, int k) {
		return repository.decrementHeldCount(userId, showId, k) == 1;
	}

	@Override
	public Set<String> findAvailableSeatLabels(long showId, Collection<String> seatLabels) {
		return Set.copyOf(repository.findAvailableSeatLabels(showId, seatLabels, SeatStatus.available));
	}

	@Override
	public boolean claimSeat(long showId, String seatLabel) {
		return repository.claimSeat(showId, seatLabel, SeatStatus.available, SeatStatus.confirmed) == 1;
	}

	@Override
	public int claimSeats(long showId, Collection<String> seatLabels) {
		return repository.claimSeats(showId, seatLabels, SeatStatus.available, SeatStatus.confirmed);
	}

	@Override
	public void insertReservationSeat(long showId, String seatLabel, String reservationId) {
		repository.insertReservationSeat(showId, seatLabel, reservationId);
	}

	@Override
	public int insertReservationSeats(long showId, Collection<String> seatLabels, String reservationId) {
		return repository.insertReservationSeats(showId, seatLabels, reservationId);
	}

	@Override
	public boolean confirmReservation(String reservationId, long amountPaise) {
		return repository.confirmReservation(reservationId, amountPaise, ReservationStatus.pending,
				ReservationStatus.confirmed) == 1;
	}

	@Override
	public boolean cancelReservation(String reservationId, String userId) {
		return repository.cancelReservation(reservationId, userId, ReservationStatus.confirmed,
				ReservationStatus.cancelled) == 1;
	}

	@Override
	public List<String> findSeatLabels(String reservationId) {
		return repository.findSeatLabels(reservationId);
	}

	@Override
	public boolean releaseSeat(long showId, String seatLabel) {
		return repository.releaseSeat(showId, seatLabel, SeatStatus.confirmed, SeatStatus.available) == 1;
	}

	@Override
	public int deleteReservationSeats(String reservationId) {
		return repository.deleteReservationSeats(reservationId);
	}

}
