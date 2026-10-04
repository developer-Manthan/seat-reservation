package com.manthan.seat_reservation.service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.manthan.seat_reservation.domain.Reservation;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.observability.ReservationMetrics;
import com.manthan.seat_reservation.repository.SeatStore;
import com.manthan.seat_reservation.service.CancelTransaction.Cancelled;
import com.manthan.seat_reservation.service.CancelTransaction.Outcome;

/**
 * Orchestrates a cancel. NOT transactional: it calls the transactional {@link CancelTransaction} through the retry
 * loop, and works out why a cancel declined, after that transaction has ended.
 */
@Service
public class CancellationService {

	private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

	private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	public record CancelResult(String reservationId, long showId, long amountPaise, List<String> releasedSeats) {
	}

	private final CancelTransaction cancelTransaction;
	private final LockRetrier retrier;
	private final SeatStore store;
	private final ReservationMetrics metrics;

	public CancellationService(CancelTransaction cancelTransaction, LockRetrier retrier, SeatStore store,
			ReservationMetrics metrics) {
		this.cancelTransaction = cancelTransaction;
		this.retrier = retrier;
		this.store = store;
		this.metrics = metrics;
	}

	public CancelResult cancel(String userId, String reservationId) {
		// Reservation ids are UUIDs. Anything else cannot exist, so it is the same 404 as an unknown id.
		if (!UUID.matcher(reservationId).matches()) {
			throw new ReservationNotFoundException();
		}
		String id = reservationId.toLowerCase();
		Outcome outcome = retrier.run(() -> cancelTransaction.cancelOnce(id, userId));
		if (outcome instanceof Cancelled cancelled) {
			metrics.cancelled();
			log.info("Reservation cancelled: user={} show={} reservation={} seats={}", userId, cancelled.showId(), id,
					cancelled.releasedSeats());
			return new CancelResult(id, cancelled.showId(), cancelled.amountPaise(), cancelled.releasedSeats());
		}
		// Nothing was cancelled. Only the owner is told the reason, everyone else sees a plain 404.
		Optional<Reservation> existing = store.findReservation(id);
		if (existing.isPresent() && existing.get().getUserId().equals(userId)
				&& existing.get().getStatus() == ReservationStatus.cancelled) {
			log.info("Cancel declined: already-cancelled user={} reservation={}", userId, id);
			throw new AlreadyCancelledException();
		}
		log.info("Cancel declined: not-found user={} reservation={}", userId, id);
		throw new ReservationNotFoundException();
	}

}
