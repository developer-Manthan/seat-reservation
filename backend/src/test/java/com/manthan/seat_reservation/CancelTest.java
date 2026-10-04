package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.manthan.seat_reservation.auth.TokenService;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * POST /reservations/{id}/cancel, single-threaded. Not @Transactional (declines and failures must really roll back).
 * Every test creates its own show and users and removes them afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CancelTest {

	private static final String ADMIN = "Bearer dev-only-admin-token";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	MeterRegistry meters;

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

	// ---- fixtures ----

	private long newShow(int perUserLimit, long pricePaise, String... seats) {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?)",
				"t-" + UUID.randomUUID(), pricePaise, perUserLimit, seats.length);
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
		return id;
	}

	private String auth(String userId) {
		return "Bearer " + tokenService.issue(userId, tokenService.expiryFromNow());
	}

	/** Books through the real endpoint and returns the reservation id. */
	private String book(String userId, long showId, String seatsJson, String key, String mode) throws Exception {
		String body = "{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"" + (mode == null ? "" : ",\"mode\":\"" + mode + "\"") + "}";
		String response = mockMvc.perform(post("/shows/" + showId + "/reserve").header("Authorization", auth(userId))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"reservation_id\":\"([^\"]+)\".*", "$1");
	}

	private ResultActions cancel(String authorization, String reservationId) throws Exception {
		var request = post("/reservations/" + reservationId + "/cancel");
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return mockMvc.perform(request);
	}

	// ---- db assertions ----

	private String seatStatus(long showId, String label) {
		return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
	}

	private String reservationStatus(String id) {
		return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, id);
	}

	private int heldCount(String userId, long showId) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(held_count), 0) FROM user_show_counts WHERE user_id = ? AND show_id = ?",
				Integer.class, userId, showId);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private void assertInvariants(long showId) {
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ? AND status = 'pending'", showId)).isZero();
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", showId))
				.isEqualTo(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", showId));
		assertThat(count("SELECT COUNT(*) FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id "
				+ "WHERE rs.show_id = ? AND r.status <> 'confirmed'", showId)).isZero();
		assertThat(count("SELECT COALESCE(SUM(held_count), 0) FROM user_show_counts WHERE show_id = ?", showId))
				.isEqualTo(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", showId));
	}

	private double counter(String name) {
		var counter = meters.find(name).counter();
		return counter == null ? 0 : counter.count();
	}

	// ---- auth and lookup ----

	@Test
	void noTokenIs401() throws Exception {
		cancel(null, UUID.randomUUID().toString()).andExpect(status().isUnauthorized());
	}

	@Test
	void adminTokenIs403() throws Exception {
		long show = newShow(4, 100, "A1");
		String id = book(newUser(), show, "[\"A1\"]", "k1", null);

		cancel(ADMIN, id).andExpect(status().isForbidden());
		assertThat(reservationStatus(id)).isEqualTo("confirmed");
	}

	@Test
	void unknownAndMalformedIdsAre404() throws Exception {
		String user = auth(newUser());

		for (String id : new String[] { UUID.randomUUID().toString(), "not-a-uuid", "12345", "..%2F..", "x".repeat(200) }) {
			cancel(user, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not-found"));
		}
	}

	// ---- success ----

	@Test
	void ownerCancelsAndGetsTheReleasedSeats() throws Exception {
		long show = newShow(4, 25000, "A1", "A2", "A3");
		String user = newUser();
		String id = book(user, show, "[\"A2\",\"A1\"]", "k1", null);
		double cancelledBefore = counter("reservations.cancelled");

		cancel(auth(user), id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reservation_id").value(id))
				.andExpect(jsonPath("$.show_id").value(show))
				.andExpect(jsonPath("$.status").value("cancelled"))
				.andExpect(jsonPath("$.amount_paise").value(50000))
				.andExpect(jsonPath("$.released_seats.length()").value(2))
				.andExpect(jsonPath("$.released_seats[0]").value("A1"))
				.andExpect(jsonPath("$.released_seats[1]").value("A2"));

		assertThat(reservationStatus(id)).isEqualTo("cancelled");
		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		assertThat(seatStatus(show, "A2")).isEqualTo("available");
		assertThat(seatStatus(show, "A3")).isEqualTo("available");
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE reservation_id = ?", id)).isZero();
		assertThat(heldCount(user, show)).isZero();
		assertThat(counter("reservations.cancelled")).isEqualTo(cancelledBefore + 1);
		assertInvariants(show);
	}

	@Test
	void historyStaysInReservationsAfterACancel() throws Exception {
		long show = newShow(4, 100, "A1");
		String user = newUser();
		String id = book(user, show, "[\"A1\"]", "k1", null);

		cancel(auth(user), id).andExpect(status().isOk());

		assertThat(count("SELECT COUNT(*) FROM reservations WHERE id = ? AND user_id = ?", id, user)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE reservation_id = ?", id)).isEqualTo(1);
	}

	@Test
	void showViewReflectsTheCancel() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String user = newUser();
		String id = book(user, show, "[\"A1\",\"A2\"]", "k1", null);
		cancel(auth(user), id).andExpect(status().isOk());

		mockMvc.perform(get("/shows/" + show).header("Authorization", ADMIN))
				.andExpect(jsonPath("$.counts.available").value(2))
				.andExpect(jsonPath("$.counts.confirmed").value(0));
	}

	// ---- ownership ----

	@Test
	void anotherUsersCancelIs404AndChangesNothing() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String alice = newUser();
		String bob = newUser();
		String id = book(alice, show, "[\"A1\",\"A2\"]", "k1", null);
		double cancelledBefore = counter("reservations.cancelled");

		cancel(auth(bob), id).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not-found"));

		assertThat(reservationStatus(id)).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A2")).isEqualTo("confirmed");
		assertThat(heldCount(alice, show)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE reservation_id = ?", id)).isEqualTo(2);
		assertThat(counter("reservations.cancelled")).isEqualTo(cancelledBefore);
		assertInvariants(show);
	}

	@Test
	void cancelNeverTouchesAnotherUsersSeatsOrQuota() throws Exception {
		long show = newShow(4, 100, "A1", "A2", "A3");
		String alice = newUser();
		String bob = newUser();
		String aliceRes = book(alice, show, "[\"A1\"]", "a1", null);
		String bobRes = book(bob, show, "[\"A2\",\"A3\"]", "b1", null);

		cancel(auth(alice), aliceRes).andExpect(status().isOk());

		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		assertThat(seatStatus(show, "A2")).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A3")).isEqualTo("confirmed");
		assertThat(reservationStatus(bobRes)).isEqualTo("confirmed");
		assertThat(heldCount(bob, show)).isEqualTo(2);
		assertThat(heldCount(alice, show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE reservation_id = ?", bobRes)).isEqualTo(2);
		assertInvariants(show);
	}

	// ---- repeat cancels ----

	@Test
	void secondCancelByTheOwnerIs409AndDoesNotReleaseTwice() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String alice = newUser();
		String bob = newUser();
		String id = book(alice, show, "[\"A1\"]", "k1", null);
		cancel(auth(alice), id).andExpect(status().isOk());
		// Bob takes the freed seat. A second cancel by Alice must not free it again or lower any counter.
		String bobRes = book(bob, show, "[\"A1\"]", "k2", null);
		double cancelledBefore = counter("reservations.cancelled");

		cancel(auth(alice), id).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("already-cancelled"));

		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
		assertThat(reservationStatus(bobRes)).isEqualTo("confirmed");
		assertThat(heldCount(alice, show)).isZero();
		assertThat(heldCount(bob, show)).isEqualTo(1);
		assertThat(counter("reservations.cancelled")).isEqualTo(cancelledBefore);
		assertInvariants(show);
	}

	@Test
	void anotherUserCancellingACancelledReservationStillSees404NotTheReason() throws Exception {
		long show = newShow(4, 100, "A1");
		String alice = newUser();
		String id = book(alice, show, "[\"A1\"]", "k1", null);
		cancel(auth(alice), id).andExpect(status().isOk());

		cancel(auth(newUser()), id).andExpect(status().isNotFound());
	}

	// ---- effects on later bookings ----

	@Test
	void aFreedSeatCanBeBookedByAnotherUser() throws Exception {
		long show = newShow(4, 100, "A1");
		String alice = newUser();
		String bob = newUser();
		String id = book(alice, show, "[\"A1\"]", "k1", null);
		cancel(auth(alice), id).andExpect(status().isOk());

		String bobRes = book(bob, show, "[\"A1\"]", "k2", null);

		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
		assertThat(jdbc.queryForObject("SELECT user_id FROM reservations WHERE id = ?", String.class, bobRes)).isEqualTo(bob);
		assertInvariants(show);
	}

	@Test
	void cancellingFreesTheQuotaSoTheUserCanBookAgain() throws Exception {
		long show = newShow(2, 100, "A1", "A2", "A3", "A4");
		String user = newUser();
		String first = book(user, show, "[\"A1\",\"A2\"]", "k1", null);
		mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth(user)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"seats\":[\"A3\"],\"idempotency_key\":\"k2\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("per-user-limit"));

		cancel(auth(user), first).andExpect(status().isOk());

		book(user, show, "[\"A3\",\"A4\"]", "k3", null);
		assertThat(heldCount(user, show)).isEqualTo(2);
		assertInvariants(show);
	}

	@Test
	void aBestEffortReservationGivesBackOnlyTheSeatsItBooked() throws Exception {
		long show = newShow(4, 100, "A1", "A2", "A3");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);
		String user = newUser();
		String id = book(user, show, "[\"A1\",\"A2\",\"A3\"]", "k1", "best_effort");
		assertThat(heldCount(user, show)).isEqualTo(2);

		cancel(auth(user), id).andExpect(status().isOk()).andExpect(jsonPath("$.released_seats.length()").value(2));

		assertThat(heldCount(user, show)).isZero();
		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		assertThat(seatStatus(show, "A3")).isEqualTo("available");
		assertThat(seatStatus(show, "A2")).isEqualTo("confirmed");
	}

	@Test
	void replayingTheOriginalReserveAfterACancelReturnsItAsCancelled() throws Exception {
		long show = newShow(4, 100, "A1");
		String user = newUser();
		String id = book(user, show, "[\"A1\"]", "k1", null);
		cancel(auth(user), id).andExpect(status().isOk());

		mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth(user)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reservation_id").value(id))
				.andExpect(jsonPath("$.status").value("cancelled"))
				.andExpect(jsonPath("$.seats.length()").value(0));

		assertThat(seatStatus(show, "A1")).isEqualTo("available");
	}

	// ---- a broken invariant rolls everything back ----

	@Test
	void aSeatThatIsNotConfirmedRollsTheWholeCancelBackAndIs500() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String user = newUser();
		String id = book(user, show, "[\"A1\",\"A2\"]", "k1", null);
		// Corrupt the data on purpose: A2 says available although the reservation still holds it.
		jdbc.update("UPDATE seats SET status = 'available' WHERE show_id = ? AND seat_label = 'A2'", show);

		cancel(auth(user), id).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value("internal-error"));

		// Nothing was applied: the reservation is still confirmed, A1 is still confirmed, the quota and rows are intact.
		assertThat(reservationStatus(id)).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
		assertThat(heldCount(user, show)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE reservation_id = ?", id)).isEqualTo(2);
	}

	// ---- a mixed run ----

	@Test
	void invariantsHoldAfterAMixedSequenceOfBookingsAndCancels() throws Exception {
		long show = newShow(3, 500, "A1", "A2", "A3", "A4", "A5", "A6");
		String alice = newUser();
		String bob = newUser();
		String a1 = book(alice, show, "[\"A1\",\"A2\"]", "a1", null);
		String b1 = book(bob, show, "[\"A3\",\"A4\",\"A5\"]", "b1", null);
		cancel(auth(alice), a1).andExpect(status().isOk());
		String a2 = book(alice, show, "[\"A2\",\"A5\",\"A6\"]", "a2", "best_effort");
		cancel(auth(bob), b1).andExpect(status().isOk());
		book(bob, show, "[\"A5\",\"A1\"]", "b2", "best_effort");
		cancel(auth(alice), a2).andExpect(status().isOk());

		assertInvariants(show);
		assertThat(heldCount(alice, show)).isZero();
		assertThat(heldCount(bob, show)).isEqualTo(2);
	}

}
