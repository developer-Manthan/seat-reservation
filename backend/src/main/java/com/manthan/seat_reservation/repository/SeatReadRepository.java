package com.manthan.seat_reservation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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

	/** Available seats of one show, for the seats_available metric. */
	interface ShowAvailability {

		Long getShowId();

		long getAvailable();

	}

	/**
	 * Available seats for every show in one statement. A show with no free seat is still returned, with 0, so its
	 * metric drops to zero instead of disappearing.
	 */
	@Query("SELECT s.id AS showId, COUNT(seat.seatLabel) AS available FROM Show s "
			+ "LEFT JOIN Seat seat ON seat.showId = s.id AND seat.status = :available GROUP BY s.id")
	List<ShowAvailability> countAvailableSeatsPerShow(@Param("available") SeatStatus available);

}
