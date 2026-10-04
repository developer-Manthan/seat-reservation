package com.manthan.seat_reservation.repository;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.manthan.seat_reservation.domain.Seat;
import com.manthan.seat_reservation.domain.SeatId;
import com.manthan.seat_reservation.domain.SeatStatus;

/**
 * Read-only seat queries for the show view. This is a read path, so a derived query is fine here. The reserve and
 * cancel path keeps explicit JPQL (see HotPathRepository).
 */
public interface SeatReadRepository extends Repository<Seat, SeatId> {

	/** Closed projection: only these two columns are selected. The getters must match Seat's property names. */
	interface SeatRow {

		String getSeatLabel();

		SeatStatus getStatus();

	}

	/** One statement, so every row is from the same snapshot. Ordering is done by the caller (natural order). */
	List<SeatRow> findByShowId(Long showId);

}
