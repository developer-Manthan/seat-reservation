package com.manthan.seat_reservation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.domain.SeatLabels;
import com.manthan.seat_reservation.domain.SeatStatus;
import com.manthan.seat_reservation.repository.BrowseRepository;
import com.manthan.seat_reservation.repository.BrowseRepository.ReservationRow;
import com.manthan.seat_reservation.repository.BrowseRepository.ReservationSeatRow;
import com.manthan.seat_reservation.repository.BrowseRepository.ShowRow;
import com.manthan.seat_reservation.repository.BrowseRepository.UserRow;

/** The read-only lists behind the UI. Each list is capped, newest first. */
@Service
public class BrowseService {

	/** Enough for a demo screen. The lists are not paged. */
	static final int MAX_ROWS = 200;

	/** One of the caller's reservations with the seats it holds now (none once it is cancelled). */
	public record MyReservation(String reservationId, long showId, ReservationStatus status, long amountPaise,
			List<String> seats, Instant createdAt) {
	}

	private final BrowseRepository browse;

	public BrowseService(BrowseRepository browse) {
		this.browse = browse;
	}

	@Transactional(readOnly = true)
	public List<UserRow> users() {
		return browse.findUsers(Limit.of(MAX_ROWS));
	}

	@Transactional(readOnly = true)
	public List<ShowRow> shows() {
		return browse.findShows(SeatStatus.available, Limit.of(MAX_ROWS));
	}

	/** The reservations of this user only, optionally for one show. The user id comes from the token, never the request. */
	@Transactional(readOnly = true)
	public List<MyReservation> reservationsOf(String userId, Long showId) {
		List<ReservationRow> reservations = browse.findReservations(userId, showId, Limit.of(MAX_ROWS));
		if (reservations.isEmpty()) {
			return List.of();
		}
		Map<String, List<String>> seatsByReservation = new HashMap<>();
		for (ReservationSeatRow seat : browse.findSeats(reservations.stream().map(ReservationRow::getId).toList())) {
			seatsByReservation.computeIfAbsent(seat.getReservationId(), id -> new ArrayList<>()).add(seat.getSeatLabel());
		}
		return reservations.stream().map(r -> {
			List<String> seats = seatsByReservation.getOrDefault(r.getId(), new ArrayList<>());
			seats.sort(SeatLabels.NATURAL_ORDER);
			return new MyReservation(r.getId(), r.getShowId(), r.getStatus(), r.getAmountPaise(), List.copyOf(seats), r.getCreatedAt());
		}).toList();
	}

}
