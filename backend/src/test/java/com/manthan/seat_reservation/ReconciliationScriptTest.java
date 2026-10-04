package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the reconciliation checks are not vacuous: each one is made to fail by corrupting data on purpose, and must
 * be the check that reports it. Not @Transactional, because the script runs on its own connection.
 */
@SpringBootTest
class ReconciliationScriptTest {

	@Autowired
	JdbcTemplate jdbc;

	private final List<Long> showIds = new ArrayList<>();
	private final List<String> userIds = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		for (long showId : showIds) {
			jdbc.update("DELETE FROM idempotency_keys WHERE reservation_id IN (SELECT id FROM reservations WHERE show_id = ?)", showId);
			jdbc.update("DELETE FROM reservation_seats WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM reservations WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM user_show_counts WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM seats WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM shows WHERE id = ?", showId);
		}
		for (String userId : userIds) {
			jdbc.update("DELETE FROM users WHERE id = ?", userId);
		}
	}

	private long newShow(long pricePaise, String... seats) {
		jdbc.update("INSERT INTO shows (name, price_paise, total_seats) VALUES (?, ?, ?)", "t-" + UUID.randomUUID(), pricePaise, seats.length);
		long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		showIds.add(id);
		for (String seat : seats) {
			jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, ?)", id, seat);
		}
		return id;
	}

	private String newUser() {
		String id = UUID.randomUUID().toString();
		userIds.add(id);
		jdbc.update("INSERT INTO users (id) VALUES (?)", id);
		return id;
	}

	private String reservation(long showId, String userId, String status, long amountPaise) {
		String id = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO reservations (id, show_id, user_id, amount_paise, status) VALUES (?, ?, ?, ?, ?)", id, showId, userId,
				amountPaise, status);
		return id;
	}

	/** A seat booked exactly as the reserve transaction leaves it. */
	private void book(long showId, String seat, String reservationId) {
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = ?", showId, seat);
		jdbc.update("INSERT INTO reservation_seats (show_id, seat_label, reservation_id) VALUES (?, ?, ?)", showId, seat, reservationId);
	}

	@Test
	void aConsistentBookingPassesEveryCheck() {
		long show = newShow(100, "A1", "A2");
		String user = newUser();
		String res = reservation(show, user, "confirmed", 200);
		book(show, "A1", res);
		book(show, "A2", res);
		jdbc.update("INSERT INTO user_show_counts (user_id, show_id, held_count) VALUES (?, ?, 2)", user, show);

		Map<String, Long> all = Reconciliation.run(jdbc);

		assertThat(all).hasSize(8);
		assertThat(Reconciliation.failures(jdbc)).isEmpty();
	}

	@Test
	void aPendingReservationIsReported() {
		long show = newShow(100, "A1");
		reservation(show, newUser(), "pending", 0);

		assertThat(Reconciliation.failures(jdbc)).containsOnlyKeys("no_pending_reservations");
	}

	@Test
	void aConfirmedSeatWithoutAReservationSeatsRowIsReported() {
		long show = newShow(100, "A1", "A2");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A1'", show);

		assertThat(Reconciliation.failures(jdbc)).containsEntry("confirmed_seats_equal_reservation_seats", 1L);
	}

	@Test
	void aReservationSeatsRowOfACancelledReservationIsReported() {
		long show = newShow(100, "A1");
		String user = newUser();
		String res = reservation(show, user, "cancelled", 100);
		book(show, "A1", res);

		assertThat(Reconciliation.failures(jdbc)).containsEntry("reservation_seats_belong_to_confirmed_reservations", 1L);
	}

	@Test
	void aReservationSeatsRowPointingAtAnAvailableSeatIsReported() {
		long show = newShow(100, "A1");
		String user = newUser();
		String res = reservation(show, user, "confirmed", 100);
		jdbc.update("INSERT INTO reservation_seats (show_id, seat_label, reservation_id) VALUES (?, 'A1', ?)", show, res);

		assertThat(Reconciliation.failures(jdbc)).containsEntry("reservation_seats_point_at_confirmed_seats", 1L);
	}

	@Test
	void aConfirmedReservationWithNoSeatsIsReported() {
		long show = newShow(100, "A1");
		reservation(show, newUser(), "confirmed", 100);

		assertThat(Reconciliation.failures(jdbc)).containsEntry("confirmed_reservations_hold_seats", 1L);
	}

	@Test
	void aShowWhoseSeatRowsDoNotMatchTotalSeatsIsReported() {
		long show = newShow(100, "A1", "A2");
		jdbc.update("UPDATE shows SET total_seats = 3 WHERE id = ?", show);

		assertThat(Reconciliation.failures(jdbc)).containsEntry("seat_counts_equal_total_seats", 1L);
	}

	@Test
	void aCounterThatDisagreesWithTheHeldSeatsIsReportedInBothDirections() {
		long show = newShow(100, "A1", "A2");
		String user = newUser();
		String res = reservation(show, user, "confirmed", 100);
		book(show, "A1", res);

		// Seat held but no counter row at all.
		assertThat(Reconciliation.failures(jdbc)).containsEntry("counters_equal_booked_seats", 1L);

		// Counter row too high.
		jdbc.update("INSERT INTO user_show_counts (user_id, show_id, held_count) VALUES (?, ?, 2)", user, show);
		assertThat(Reconciliation.failures(jdbc)).containsEntry("counters_equal_booked_seats", 1L);

		// Counter row correct again.
		jdbc.update("UPDATE user_show_counts SET held_count = 1 WHERE user_id = ? AND show_id = ?", user, show);
		assertThat(Reconciliation.failures(jdbc)).isEmpty();

		// A counter above zero with no seats at all.
		String other = newUser();
		jdbc.update("INSERT INTO user_show_counts (user_id, show_id, held_count) VALUES (?, ?, 1)", other, show);
		assertThat(Reconciliation.failures(jdbc)).containsEntry("counters_equal_booked_seats", 1L);
	}

	@Test
	void aWrongAmountIsReported() {
		long show = newShow(250, "A1", "A2");
		String user = newUser();
		String res = reservation(show, user, "confirmed", 999);
		book(show, "A1", res);
		book(show, "A2", res);
		jdbc.update("INSERT INTO user_show_counts (user_id, show_id, held_count) VALUES (?, ?, 2)", user, show);

		assertThat(Reconciliation.failures(jdbc)).containsOnlyKeys("amount_equals_price_times_seats");

		jdbc.update("UPDATE reservations SET amount_paise = 500 WHERE id = ?", res);
		assertThat(Reconciliation.failures(jdbc)).isEmpty();
	}

}
