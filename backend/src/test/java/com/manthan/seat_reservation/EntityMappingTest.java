package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.domain.IdempotencyKey;
import com.manthan.seat_reservation.domain.IdempotencyKeyId;
import com.manthan.seat_reservation.domain.Reservation;
import com.manthan.seat_reservation.domain.ReservationSeat;
import com.manthan.seat_reservation.domain.ReservationSeatId;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.domain.Seat;
import com.manthan.seat_reservation.domain.SeatId;
import com.manthan.seat_reservation.domain.SeatStatus;
import com.manthan.seat_reservation.domain.Show;
import com.manthan.seat_reservation.domain.User;
import com.manthan.seat_reservation.domain.UserShowCount;
import com.manthan.seat_reservation.domain.UserShowCountId;

import jakarta.persistence.EntityManager;

/**
 * Boots with ddl-auto=validate (so every entity must match the Liquibase schema) and reads each entity back
 * from real rows. Everything rolls back at the end of each test.
 */
@SpringBootTest
@Transactional
class EntityMappingTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EntityManager em;

	@Test
	void everyEntityMapsToItsTable() {
		String userId = "u-" + UUID.randomUUID();
		String reservationId = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES ('Demo', 25000, 4, 2)");
		Long showId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'A1'), (?, 'A2')", showId, showId);
		jdbc.update("INSERT INTO reservations (id, show_id, user_id, amount_paise, status) VALUES (?, ?, ?, 25000, 'confirmed')",
				reservationId, showId, userId);
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A1'", showId);
		jdbc.update("INSERT INTO reservation_seats (show_id, seat_label, reservation_id) VALUES (?, 'A1', ?)",
				showId, reservationId);
		jdbc.update("INSERT INTO user_show_counts (user_id, show_id, held_count) VALUES (?, ?, 1)", userId, showId);
		jdbc.update("INSERT INTO idempotency_keys (user_id, idem_key, request_hash, reservation_id) VALUES (?, 'k1', ?, ?)",
				userId, "a".repeat(64), reservationId);

		assertThat(em.find(User.class, userId).getCreatedAt()).isNotNull();

		Show show = em.find(Show.class, showId);
		assertThat(show.getPricePaise()).isEqualTo(25000L);
		assertThat(show.getPerUserLimit()).isEqualTo(4);
		assertThat(show.getTotalSeats()).isEqualTo(2);

		assertThat(em.find(Seat.class, new SeatId(showId, "A1")).getStatus()).isEqualTo(SeatStatus.confirmed);
		assertThat(em.find(Seat.class, new SeatId(showId, "A2")).getStatus()).isEqualTo(SeatStatus.available);

		Reservation reservation = em.find(Reservation.class, reservationId);
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.confirmed);
		assertThat(reservation.getAmountPaise()).isEqualTo(25000L);
		assertThat(reservation.getUserId()).isEqualTo(userId);

		assertThat(em.find(ReservationSeat.class, new ReservationSeatId(showId, "A1")).getReservationId())
				.isEqualTo(reservationId);
		assertThat(em.find(UserShowCount.class, new UserShowCountId(userId, showId)).getHeldCount()).isEqualTo(1);

		IdempotencyKey key = em.find(IdempotencyKey.class, new IdempotencyKeyId(userId, "k1"));
		assertThat(key.getRequestHash()).hasSize(64);
		assertThat(key.getReservationId()).isEqualTo(reservationId);
	}

	@Test
	void seatLabelsAreCaseSensitive() {
		jdbc.update("INSERT INTO shows (name, price_paise, total_seats) VALUES ('Demo', 1, 2)");
		Long showId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'a1'), (?, 'A1')", showId, showId);

		assertThat(em.find(Seat.class, new SeatId(showId, "a1"))).isNotNull();
		assertThat(em.find(Seat.class, new SeatId(showId, "A1"))).isNotNull();
	}

}
