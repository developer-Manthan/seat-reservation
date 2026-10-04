package com.manthan.seat_reservation.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.manthan.seat_reservation.domain.Reservation;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.domain.SeatStatus;

/**
 * Read-only list queries for the UI: users, shows and a user's reservations. Nothing here is on the reserve or cancel
 * path, and nothing here changes data. Every list is capped with a Limit, newest first.
 */
public interface BrowseRepository extends Repository<Reservation, String> {

	interface UserRow {

		String getId();

		Instant getCreatedAt();

	}

	interface ShowRow {

		Long getId();

		String getName();

		long getPricePaise();

		int getPerUserLimit();

		int getTotalSeats();

		long getAvailable();

	}

	interface ReservationRow {

		String getId();

		Long getShowId();

		ReservationStatus getStatus();

		long getAmountPaise();

		Instant getCreatedAt();

	}

	interface ReservationSeatRow {

		String getReservationId();

		String getSeatLabel();

	}

	@Query("SELECT u.id AS id, u.createdAt AS createdAt FROM User u ORDER BY u.createdAt DESC, u.id")
	List<UserRow> findUsers(Limit limit);

	/** Every show with its count of available seats, in one statement. A sold-out show is returned with 0. */
	@Query("SELECT s.id AS id, s.name AS name, s.pricePaise AS pricePaise, s.perUserLimit AS perUserLimit, s.totalSeats AS totalSeats, "
			+ "COUNT(seat.seatLabel) AS available FROM Show s LEFT JOIN Seat seat ON seat.showId = s.id AND seat.status = :available "
			+ "GROUP BY s.id, s.name, s.pricePaise, s.perUserLimit, s.totalSeats ORDER BY s.id DESC")
	List<ShowRow> findShows(@Param("available") SeatStatus available, Limit limit);

	@Query("SELECT r.id AS id, r.showId AS showId, r.status AS status, r.amountPaise AS amountPaise, r.createdAt AS createdAt "
			+ "FROM Reservation r WHERE r.userId = :userId AND (:showId IS NULL OR r.showId = :showId) ORDER BY r.createdAt DESC, r.id")
	List<ReservationRow> findReservations(@Param("userId") String userId, @Param("showId") Long showId, Limit limit);

	@Query("SELECT rs.reservationId AS reservationId, rs.seatLabel AS seatLabel FROM ReservationSeat rs "
			+ "WHERE rs.reservationId IN :reservationIds ORDER BY rs.seatLabel")
	List<ReservationSeatRow> findSeats(@Param("reservationIds") Collection<String> reservationIds);

}
