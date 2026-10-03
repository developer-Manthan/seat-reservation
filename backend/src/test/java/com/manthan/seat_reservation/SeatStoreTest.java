package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.repository.DataInvariantException;
import com.manthan.seat_reservation.repository.SeatStore;

/**
 * One test per hot-path query against the real MySQL test schema. Each test runs in a transaction that rolls
 * back, so nothing is left behind. (The multi-threaded race tests commit and clean up, they live elsewhere.)
 */
@SpringBootTest
@Transactional
class SeatStoreTest {

	@Autowired
	SeatStore store;

	@Autowired
	JdbcTemplate jdbc;

	// ---- seat claim ----

	@Test
	void claimSeatWinsOnceThenDeclines() {
		long showId = newShow(4, "A1");

		assertThat(store.claimSeat(showId, "A1")).isTrue();
		assertThat(store.claimSeat(showId, "A1")).isFalse();
		assertThat(seatStatus(showId, "A1")).isEqualTo("confirmed");
	}

	@Test
	void claimSeatDeclinesWhenSeatIsNotAvailable() {
		long showId = newShow(4, "A1", "A2");
		jdbc.update("UPDATE seats SET status = 'held' WHERE show_id = ? AND seat_label = 'A1'", showId);
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", showId);

		assertThat(store.claimSeat(showId, "A1")).isFalse();
		assertThat(store.claimSeat(showId, "A2")).isFalse();
		assertThat(store.claimSeat(showId, "NOPE")).isFalse();
		assertThat(seatStatus(showId, "A1")).isEqualTo("held");
	}

	@Test
	void releaseSeatOnlyFlipsConfirmedSeats() {
		long showId = newShow(4, "A1", "A2");
		store.claimSeat(showId, "A1");

		assertThat(store.releaseSeat(showId, "A1")).isTrue();
		assertThat(store.releaseSeat(showId, "A1")).isFalse();
		assertThat(store.releaseSeat(showId, "A2")).isFalse();
		assertThat(seatStatus(showId, "A1")).isEqualTo("available");
	}

	// ---- per-user limit counter ----

	@Test
	void limitDeclineReturnsFalseAndLeavesTheCounterUntouched() {
		String user = newUser();
		long showId = newShow(4, "A1");
		store.ensureUserShowCount(user, showId);

		assertThat(store.tryIncrementUserCount(user, showId, 3, 4)).isTrue();
		assertThat(store.tryIncrementUserCount(user, showId, 2, 4)).isFalse();
		assertThat(heldCount(user, showId)).isEqualTo(3);
		assertThat(store.tryIncrementUserCount(user, showId, 1, 4)).isTrue();
		assertThat(heldCount(user, showId)).isEqualTo(4);
		assertThat(store.tryIncrementUserCount(user, showId, 1, 4)).isFalse();
	}

	@Test
	void requestingMoreThanTheLimitInOneGoIsDeclined() {
		String user = newUser();
		long showId = newShow(4, "A1");
		store.ensureUserShowCount(user, showId);

		assertThat(store.tryIncrementUserCount(user, showId, 5, 4)).isFalse();
		assertThat(heldCount(user, showId)).isZero();
	}

	@Test
	void decrementNeverGoesBelowZero() {
		String user = newUser();
		long showId = newShow(4, "A1");
		store.ensureUserShowCount(user, showId);
		store.tryIncrementUserCount(user, showId, 2, 4);

		assertThat(store.decrementUserCount(user, showId, 3)).isFalse();
		assertThat(heldCount(user, showId)).isEqualTo(2);
		assertThat(store.decrementUserCount(user, showId, 2)).isTrue();
		assertThat(heldCount(user, showId)).isZero();
	}

	// ---- reservation confirm and cancel ----

	@Test
	void confirmReservationOnlyWorksOnPending() {
		String user = newUser();
		long showId = newShow(4, "A1");
		String id = newReservation(user, showId, "pending");

		assertThat(store.confirmReservation(id, 25000)).isTrue();
		assertThat(store.confirmReservation(id, 25000)).isFalse();
		assertThat(reservationStatus(id)).isEqualTo("confirmed");
		assertThat(jdbc.queryForObject("SELECT amount_paise FROM reservations WHERE id = ?", Long.class, id))
				.isEqualTo(25000L);
	}

	@Test
	void cancelNeverTouchesAnotherUsersSeat() {
		String alice = newUser();
		String bob = newUser();
		long showId = newShow(4, "A1", "A2");
		String aliceRes = bookedReservation(alice, showId, "A1");
		String bobRes = bookedReservation(bob, showId, "A2");

		// Bob cannot cancel Alice's reservation: guarded update affects 0 rows, nothing changes.
		assertThat(store.cancelReservation(aliceRes, bob)).isFalse();
		assertThat(reservationStatus(aliceRes)).isEqualTo("confirmed");
		assertThat(seatStatus(showId, "A1")).isEqualTo("confirmed");

		// Alice cancels her own: only her seat and her reservation_seats row are touched.
		assertThat(store.cancelReservation(aliceRes, alice)).isTrue();
		assertThat(store.cancelReservation(aliceRes, alice)).isFalse();
		assertThat(store.findSeatLabels(aliceRes)).containsExactly("A1");
		assertThat(store.releaseSeat(showId, "A1")).isTrue();
		assertThat(store.deleteReservationSeats(aliceRes)).isEqualTo(1);

		assertThat(seatStatus(showId, "A1")).isEqualTo("available");
		assertThat(seatStatus(showId, "A2")).isEqualTo("confirmed");
		assertThat(reservationStatus(bobRes)).isEqualTo("confirmed");
		assertThat(store.findSeatLabels(bobRes)).containsExactly("A2");
		assertThat(store.findSeatLabels(aliceRes)).isEmpty();
	}

	@Test
	void cancelOfAnUnknownReservationIsDeclined() {
		assertThat(store.cancelReservation(UUID.randomUUID().toString(), newUser())).isFalse();
	}

	@Test
	void seatLabelsComeBackSorted() {
		String user = newUser();
		long showId = newShow(4, "A1", "A2", "A3");
		String id = newReservation(user, showId, "confirmed");
		store.insertReservationSeat(showId, "A3", id);
		store.insertReservationSeat(showId, "A1", id);
		store.insertReservationSeat(showId, "A2", id);

		assertThat(store.findSeatLabels(id)).containsExactly("A1", "A2", "A3");
	}

	@Test
	void secondReservationSeatRowForTheSameSeatIsRejectedByThePrimaryKey() {
		String user = newUser();
		long showId = newShow(4, "A1");
		String first = newReservation(user, showId, "confirmed");
		String second = newReservation(user, showId, "confirmed");
		store.insertReservationSeat(showId, "A1", first);

		assertThatThrownBy(() -> store.insertReservationSeat(showId, "A1", second))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- INSERT IGNORE behaviour (setup check 3) ----

	@Test
	void insertIgnoreIsIdempotentAndThrowsNothing() {
		String user = newUser();
		long showId = newShow(4, "A1");

		assertThatCode(() -> {
			store.ensureUser(user);
			store.ensureUser(user);
			store.ensureUserShowCount(user, showId);
			store.ensureUserShowCount(user, showId);
		}).doesNotThrowAnyException();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, user)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_show_counts WHERE user_id = ?", Integer.class, user))
				.isEqualTo(1);
	}

	@Test
	void idempotencyKeyInsertReportsInsertedThenExisting() {
		String user = newUser();
		long showId = newShow(4, "A1");
		String id = newReservation(user, showId, "pending");

		assertThat(store.insertIdempotencyKey(user, "key-1", "h".repeat(64), id)).isTrue();
		assertThat(store.insertIdempotencyKey(user, "key-1", "h".repeat(64), id)).isFalse();
		assertThat(store.findIdempotencyKey(user, "key-1")).isPresent();
	}

	@Test
	void insertIgnoreThatSwallowsAForeignKeyErrorIsCaughtAsABug() {
		String user = newUser();

		// reservation_id points at no reservation: INSERT IGNORE returns 0 without throwing, the row is not there,
		// so the load-or-throw guard must raise instead of treating it as a replay.
		assertThatThrownBy(() -> store.insertIdempotencyKey(user, "key-x", "h".repeat(64), UUID.randomUUID().toString()))
				.isInstanceOf(DataInvariantException.class);
		assertThat(store.findIdempotencyKey(user, "key-x")).isEmpty();
	}

	@Test
	void counterRowForAnUnknownShowIsCaughtAsABug() {
		String user = newUser();

		assertThatThrownBy(() -> store.ensureUserShowCount(user, Long.MAX_VALUE))
				.isInstanceOf(DataInvariantException.class);
	}

	// ---- helpers ----

	private String newUser() {
		String id = "u-" + UUID.randomUUID();
		store.ensureUser(id);
		return id;
	}

	private long newShow(int perUserLimit, String... seatLabels) {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES ('Test', 25000, ?, ?)",
				perUserLimit, seatLabels.length);
		Long showId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		for (String label : seatLabels) {
			jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, ?)", showId, label);
		}
		return showId;
	}

	private String newReservation(String userId, long showId, String status) {
		String id = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO reservations (id, show_id, user_id, status) VALUES (?, ?, ?, ?)", id, showId, userId,
				status);
		return id;
	}

	/** A confirmed reservation that owns the given seat, as the reserve transaction would leave it. */
	private String bookedReservation(String userId, long showId, String seatLabel) {
		String id = newReservation(userId, showId, "confirmed");
		assertThat(store.claimSeat(showId, seatLabel)).isTrue();
		store.insertReservationSeat(showId, seatLabel, id);
		return id;
	}

	private String seatStatus(long showId, String label) {
		return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId,
				label);
	}

	private String reservationStatus(String id) {
		return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, id);
	}

	private int heldCount(String userId, long showId) {
		return jdbc.queryForObject("SELECT held_count FROM user_show_counts WHERE user_id = ? AND show_id = ?",
				Integer.class, userId, showId);
	}

}
