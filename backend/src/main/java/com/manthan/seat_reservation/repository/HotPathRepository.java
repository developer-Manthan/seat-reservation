package com.manthan.seat_reservation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.manthan.seat_reservation.domain.IdempotencyKey;
import com.manthan.seat_reservation.domain.Reservation;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.domain.Seat;
import com.manthan.seat_reservation.domain.SeatId;
import com.manthan.seat_reservation.domain.SeatStatus;
import com.manthan.seat_reservation.domain.User;
import com.manthan.seat_reservation.domain.UserShowCount;

/**
 * Every hot-path query lives here. Writes are guarded UPDATEs that return rows affected (1 = won, 0 = declined),
 * never load-check-save. Enums are bound as parameters, never written into a query. These methods need an
 * enclosing transaction, which the service layer provides.
 */
public interface HotPathRepository extends Repository<Seat, SeatId> {

	// ---- guarded updates ----

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE Seat s SET s.status = :confirmed "
			+ "WHERE s.showId = :showId AND s.seatLabel = :seatLabel AND s.status = :available")
	int claimSeat(@Param("showId") Long showId, @Param("seatLabel") String seatLabel,
			@Param("available") SeatStatus available, @Param("confirmed") SeatStatus confirmed);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE Seat s SET s.status = :available "
			+ "WHERE s.showId = :showId AND s.seatLabel = :seatLabel AND s.status = :confirmed")
	int releaseSeat(@Param("showId") Long showId, @Param("seatLabel") String seatLabel,
			@Param("confirmed") SeatStatus confirmed, @Param("available") SeatStatus available);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE UserShowCount c SET c.heldCount = c.heldCount + :n "
			+ "WHERE c.userId = :userId AND c.showId = :showId AND c.heldCount + :n <= :limit")
	int incrementHeldCount(@Param("userId") String userId, @Param("showId") Long showId,
			@Param("n") int n, @Param("limit") int limit);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE UserShowCount c SET c.heldCount = c.heldCount - :k "
			+ "WHERE c.userId = :userId AND c.showId = :showId AND c.heldCount >= :k")
	int decrementHeldCount(@Param("userId") String userId, @Param("showId") Long showId, @Param("k") int k);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE Reservation r SET r.status = :confirmed, r.amountPaise = :amountPaise "
			+ "WHERE r.id = :id AND r.status = :pending")
	int confirmReservation(@Param("id") String id, @Param("amountPaise") long amountPaise,
			@Param("pending") ReservationStatus pending, @Param("confirmed") ReservationStatus confirmed);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE Reservation r SET r.status = :cancelled "
			+ "WHERE r.id = :id AND r.userId = :userId AND r.status = :confirmed")
	int cancelReservation(@Param("id") String id, @Param("userId") String userId,
			@Param("confirmed") ReservationStatus confirmed, @Param("cancelled") ReservationStatus cancelled);

	// ---- inserts ----

	/** INSERT IGNORE also swallows other errors, so 0 rows must always be followed by loading the existing row. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "INSERT IGNORE INTO users (id) VALUES (:id)", nativeQuery = true)
	int insertUserIfAbsent(@Param("id") String id);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "INSERT IGNORE INTO user_show_counts (user_id, show_id, held_count) VALUES (:userId, :showId, 0)",
			nativeQuery = true)
	int insertUserShowCountIfAbsent(@Param("userId") String userId, @Param("showId") Long showId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "INSERT IGNORE INTO idempotency_keys (user_id, idem_key, request_hash, reservation_id) "
			+ "VALUES (:userId, :idemKey, :requestHash, :reservationId)", nativeQuery = true)
	int insertIdempotencyKeyIfAbsent(@Param("userId") String userId, @Param("idemKey") String idemKey,
			@Param("requestHash") String requestHash, @Param("reservationId") String reservationId);

	/** Plain INSERT on purpose (never INSERT IGNORE on reservations). Status is bound as the enum name. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "INSERT INTO reservations (id, show_id, user_id, amount_paise, status) "
			+ "VALUES (:id, :showId, :userId, 0, :status)", nativeQuery = true)
	int insertReservation(@Param("id") String id, @Param("showId") Long showId, @Param("userId") String userId,
			@Param("status") String status);

	/** Plain INSERT on purpose: the primary key is a backstop, a duplicate means the guarded update logic is broken. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "INSERT INTO reservation_seats (show_id, seat_label, reservation_id) "
			+ "VALUES (:showId, :seatLabel, :reservationId)", nativeQuery = true)
	int insertReservationSeat(@Param("showId") Long showId, @Param("seatLabel") String seatLabel,
			@Param("reservationId") String reservationId);

	// ---- delete ----

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("DELETE FROM ReservationSeat rs WHERE rs.reservationId = :reservationId")
	int deleteReservationSeats(@Param("reservationId") String reservationId);

	// ---- reads ----

	@Query("SELECT u FROM User u WHERE u.id = :id")
	Optional<User> findUser(@Param("id") String id);

	@Query("SELECT c FROM UserShowCount c WHERE c.userId = :userId AND c.showId = :showId")
	Optional<UserShowCount> findUserShowCount(@Param("userId") String userId, @Param("showId") Long showId);

	@Query("SELECT k FROM IdempotencyKey k WHERE k.userId = :userId AND k.idemKey = :idemKey")
	Optional<IdempotencyKey> findIdempotencyKey(@Param("userId") String userId, @Param("idemKey") String idemKey);

	@Query("SELECT r FROM Reservation r WHERE r.id = :id")
	Optional<Reservation> findReservation(@Param("id") String id);

	@Query("SELECT rs.seatLabel FROM ReservationSeat rs WHERE rs.reservationId = :reservationId "
			+ "ORDER BY rs.seatLabel")
	List<String> findSeatLabels(@Param("reservationId") String reservationId);

}
