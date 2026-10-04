package com.manthan.seat_reservation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.domain.Reservation;
import com.manthan.seat_reservation.repository.DataInvariantException;
import com.manthan.seat_reservation.repository.SeatStore;

/**
 * One cancel attempt, in one READ COMMITTED transaction, in its own bean so the retry loop can wrap it from outside.
 * Same lock order as reserve: reservation, counter, seats (sorted), reservation_seats. A freed seat can be claimed
 * again only after this transaction commits, and a cancel can never touch a seat that belongs to someone else,
 * because it only releases the seats listed in its own reservation_seats rows.
 */
@Service
public class CancelTransaction {

	public sealed interface Outcome permits Cancelled, NotCancelled {
	}

	/** The reservation was confirmed and owned by the caller, and is now cancelled with its seats released. */
	public record Cancelled(String reservationId, long showId, long amountPaise, List<String> releasedSeats)
			implements Outcome {
	}

	/** The guarded update matched no row: unknown id, someone else's reservation, or already cancelled. */
	public record NotCancelled() implements Outcome {
	}

	private final SeatStore store;

	public CancelTransaction(SeatStore store) {
		this.store = store;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Outcome cancelOnce(String reservationId, String userId) {
		// 1. Only one canceller can win this update. It also checks the owner.
		if (!store.cancelReservation(reservationId, userId)) {
			return new NotCancelled();
		}
		Reservation reservation = store.findReservation(reservationId)
				.orElseThrow(() -> new DataInvariantException("Reservation " + reservationId + " vanished during cancel"));
		long showId = reservation.getShowId();

		// 2. The seats this reservation holds, in sorted order, and the quota they used.
		List<String> seats = store.findSeatLabels(reservationId);
		if (seats.isEmpty()) {
			throw new DataInvariantException("Confirmed reservation " + reservationId + " has no reservation_seats rows");
		}
		if (!store.decrementUserCount(userId, showId, seats.size())) {
			throw new DataInvariantException("Counter of user " + userId + " on show " + showId + " is below the "
					+ seats.size() + " seat(s) of reservation " + reservationId);
		}

		// 3. Release each seat, in sorted order. Each one must be confirmed, so each update affects exactly one row.
		for (String seat : seats) {
			if (!store.releaseSeat(showId, seat)) {
				throw new DataInvariantException("Seat " + seat + " of show " + showId + " was not confirmed while cancelling reservation "
						+ reservationId);
			}
		}

		// 4. The seats are no longer booked, so the rows that said so go too. History stays in reservations.
		int deleted = store.deleteReservationSeats(reservationId);
		if (deleted != seats.size()) {
			throw new DataInvariantException("Deleted " + deleted + " reservation_seats rows, expected " + seats.size()
					+ " for reservation " + reservationId);
		}
		return new Cancelled(reservationId, showId, reservation.getAmountPaise(), List.copyOf(seats));
	}

}
