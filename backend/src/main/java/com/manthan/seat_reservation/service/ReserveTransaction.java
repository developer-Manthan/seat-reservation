package com.manthan.seat_reservation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.manthan.seat_reservation.domain.IdempotencyKey;
import com.manthan.seat_reservation.domain.ReservationMode;
import com.manthan.seat_reservation.repository.DataInvariantException;
import com.manthan.seat_reservation.repository.SeatStore;

/**
 * One reserve attempt, in one READ COMMITTED transaction. It lives in its own bean so a retry loop can call it from
 * outside, and every attempt is a fresh transaction. Lock order: reservation (own new row), idempotency, counter,
 * seats (sorted), reservation_seats. Every decline throws, which rolls back the pending reservation and the key.
 */
@Service
public class ReserveTransaction {

	private static final Logger log = LoggerFactory.getLogger(ReserveTransaction.class);

	/** Everything one attempt needs. {@code seats} are normalized, distinct and sorted. */
	public record Attempt(String userId, long showId, long pricePaise, int perUserLimit, List<String> seats,
			String idempotencyKey, String requestHash, ReservationMode mode) {
	}

	public sealed interface Outcome permits Booked, KeyExists {
	}

	/** The reservation is confirmed and committed. */
	public record Booked(String reservationId, List<String> bookedSeats, List<String> unavailableSeats, long amountPaise)
			implements Outcome {
	}

	/** The key already existed, so the provisional reservation was rolled back. The caller replays or conflicts. */
	public record KeyExists(String storedRequestHash, String reservationId) implements Outcome {
	}

	private final SeatStore store;

	public ReserveTransaction(SeatStore store) {
		this.store = store;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Outcome reserveOnce(Attempt attempt) {
		String reservationId = UUID.randomUUID().toString();

		// 2. The foreign keys on reservation_seats and idempotency_keys need this row first.
		store.insertPendingReservation(reservationId, attempt.showId(), attempt.userId());

		// 3. Idempotency. The primary key is the real guard.
		if (!store.insertIdempotencyKey(attempt.userId(), attempt.idempotencyKey(), attempt.requestHash(), reservationId)) {
			IdempotencyKey existing = store.findIdempotencyKey(attempt.userId(), attempt.idempotencyKey())
					.orElseThrow(() -> new DataInvariantException("Idempotency key vanished after INSERT IGNORE returned 0"));
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			return new KeyExists(existing.getRequestHash(), existing.getReservationId());
		}

		// 4. Per-user limit. The counter row stays locked until commit, so parallel requests from one user queue.
		int requested = attempt.seats().size();
		store.ensureUserShowCount(attempt.userId(), attempt.showId());
		if (!store.tryIncrementUserCount(attempt.userId(), attempt.showId(), requested, attempt.perUserLimit())) {
			throw new PerUserLimitException(requested, attempt.perUserLimit());
		}

		// 5. Seats in sorted order. The guarded update is the atomic decision.
		List<String> won = new ArrayList<>();
		List<String> unavailable = new ArrayList<>();
		for (String label : attempt.seats()) {
			if (store.claimSeat(attempt.showId(), label)) {
				insertReservationSeat(attempt, reservationId, label);
				won.add(label);
			}
			else if (attempt.mode() == ReservationMode.all_or_nothing) {
				throw new SeatTakenException(List.of(label));
			}
			else {
				unavailable.add(label);
			}
		}
		if (won.isEmpty()) {
			throw new SeatTakenException(unavailable);
		}

		// 6. best_effort: give the quota for the seats not won back.
		if (!unavailable.isEmpty() && !store.decrementUserCount(attempt.userId(), attempt.showId(), unavailable.size())) {
			throw new DataInvariantException("Could not give back " + unavailable.size() + " seat(s) of quota, counter below zero");
		}

		// 7. Confirm with the real amount. pending never survives the commit.
		long amount = Math.multiplyExact(attempt.pricePaise(), (long) won.size());
		if (!store.confirmReservation(reservationId, amount)) {
			throw new DataInvariantException("Reservation " + reservationId + " was not pending at confirm time");
		}
		return new Booked(reservationId, List.copyOf(won), List.copyOf(unavailable), amount);
	}

	/**
	 * A duplicate key here means the guarded update logic is broken: log at ERROR and decline with seat-taken,
	 * never a 5xx. We rethrow at once and never continue in this transaction.
	 */
	private void insertReservationSeat(Attempt attempt, String reservationId, String label) {
		try {
			store.insertReservationSeat(attempt.showId(), label, reservationId);
		}
		catch (DataIntegrityViolationException e) {
			log.error("Invariant violation: reservation_seats already has show={} seat={} although the guarded update won it",
					attempt.showId(), label, e);
			throw new SeatTakenException(List.of(label));
		}
	}

}
