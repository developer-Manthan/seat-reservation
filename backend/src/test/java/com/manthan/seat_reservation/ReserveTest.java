package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.manthan.seat_reservation.auth.TokenService;
import com.manthan.seat_reservation.domain.ReservationMode;
import com.manthan.seat_reservation.repository.SeatStore;
import com.manthan.seat_reservation.service.ReserveTransaction;
import com.manthan.seat_reservation.service.ReserveTransaction.Attempt;
import com.manthan.seat_reservation.service.ReserveTransaction.KeyExists;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * POST /shows/{id}/reserve, single-threaded. Deliberately NOT @Transactional: a test-wide transaction would hide
 * whether declines really roll back. Every test creates its own show and users and removes them afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReserveTest {

	private static final String ADMIN = "Bearer dev-only-admin-token";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	MeterRegistry meters;

	@Autowired
	ReserveTransaction reserveTransaction;

	@MockitoSpyBean
	SeatStore seatStore;

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

	private static String json(String seatsJson, String key, String mode) {
		return "{\"seats\":" + seatsJson + (key == null ? "" : ",\"idempotency_key\":\"" + key + "\"")
				+ (mode == null ? "" : ",\"mode\":\"" + mode + "\"") + "}";
	}

	private ResultActions reserve(String authorization, long showId, String body) throws Exception {
		var request = post("/shows/" + showId + "/reserve").contentType(MediaType.APPLICATION_JSON).content(body);
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return mockMvc.perform(request);
	}

	private ResultActions reserve(String userId, long showId, String seatsJson, String key, String mode) throws Exception {
		return reserve(auth(userId), showId, json(seatsJson, key, mode));
	}

	private String reservationId(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString().replaceAll(".*\"reservation_id\":\"([^\"]+)\".*", "$1");
	}

	// ---- db assertions ----

	private String seatStatus(long showId, String label) {
		return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
	}

	private int heldCount(String userId, long showId) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(held_count), 0) FROM user_show_counts WHERE user_id = ? AND show_id = ?",
				Integer.class, userId, showId);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private int reservations(long showId) {
		return count("SELECT COUNT(*) FROM reservations WHERE show_id = ?", showId);
	}

	private int keys(String userId, String key) {
		return count("SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?", userId, key);
	}

	/** The invariants that must hold after every request: no pending rows, confirmed seats match reservation_seats. */
	private void assertInvariants(long showId) {
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ? AND status = 'pending'", showId)).isZero();
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", showId))
				.isEqualTo(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", showId));
		assertThat(count("SELECT COUNT(*) FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id "
				+ "WHERE rs.show_id = ? AND r.status <> 'confirmed'", showId)).isZero();
	}

	private double counter(String name, String... tags) {
		var search = meters.find(name);
		for (int i = 0; i < tags.length; i += 2) {
			search = search.tag(tags[i], tags[i + 1]);
		}
		var counter = search.counter();
		return counter == null ? 0 : counter.count();
	}

	// ---- auth, lookup, validation ----

	@Test
	void noTokenIs401() throws Exception {
		long show = newShow(4, 100, "A1");

		reserve(null, show, json("[\"A1\"]", "k1", null)).andExpect(status().isUnauthorized());
	}

	@Test
	void adminTokenIs403BecauseItHasNoUserId() throws Exception {
		long show = newShow(4, 100, "A1");

		reserve(ADMIN, show, json("[\"A1\"]", "k1", null)).andExpect(status().isForbidden());
		assertThat(seatStatus(show, "A1")).isEqualTo("available");
	}

	@Test
	void unknownShowIs404() throws Exception {
		reserve(newUser(), 999999999L, "[\"A1\"]", "k1", null)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not-found"));
	}

	@Test
	void idempotencyKeyIsRequiredAndMustBeWellFormed() throws Exception {
		long show = newShow(4, 100, "A1");
		String user = newUser();

		reserve(auth(user), show, json("[\"A1\"]", null, null))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(startsWith("idempotency_key: ")));
		for (String bad : new String[] { "has space", "a/b", "x".repeat(129) }) {
			reserve(user, show, "[\"A1\"]", bad, null)
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(startsWith("idempotency_key: ")));
		}
		assertThat(seatStatus(show, "A1")).isEqualTo("available");
	}

	@Test
	void keyMayComeAsAHeaderAndHeaderAndBodyMustMatch() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String user = newUser();

		mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth(user)).header("Idempotency-Key", "hdr-1")
						.contentType(MediaType.APPLICATION_JSON).content(json("[\"A1\"]", null, null)))
				.andExpect(status().isCreated());
		assertThat(keys(user, "hdr-1")).isEqualTo(1);

		mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth(user)).header("Idempotency-Key", "hdr-2")
						.contentType(MediaType.APPLICATION_JSON).content(json("[\"A2\"]", "body-2", null)))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth(user)).header("Idempotency-Key", "same")
						.contentType(MediaType.APPLICATION_JSON).content(json("[\"A2\"]", "same", null)))
				.andExpect(status().isCreated());
	}

	@Test
	void invalidSeatListsAndModesAre400AndChangeNothing() throws Exception {
		long show = newShow(2, 100, "A1", "A2", "A3");
		String user = newUser();
		String[][] cases = {
				{ "[]", null, "seats" },
				{ "null", null, "seats" },
				{ "[\"A 1\"]", null, "seats[0]" },
				{ "[\"\"]", null, "seats[0]" },
				{ "[null]", null, "seats[0]" },
				{ "[\"A1\",\"A2\",\"A3\"]", null, "seats: 3 distinct seats requested" },
				{ "[\"A1\"]", "fastest", "mode" },
				{ "[\"A1\"]", "ALL_OR_NOTHING", "mode" },
		};
		int n = 0;
		for (String[] c : cases) {
			reserve(user, show, c[0], "bad-" + n++, c[1])
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("bad-request"))
					.andExpect(jsonPath("$.message").value(startsWith(c[2])));
		}
		assertThat(reservations(show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", show)).isEqualTo(3);
	}

	@Test
	void tooManyLabelsInOneRequestIs400() throws Exception {
		long show = newShow(4, 100, "A1");
		StringBuilder seats = new StringBuilder("[");
		for (int i = 0; i < 501; i++) {
			seats.append(i > 0 ? "," : "").append("\"S").append(i).append("\"");
		}

		reserve(newUser(), show, seats.append("]").toString(), "k", null).andExpect(status().isBadRequest());
	}

	@Test
	void duplicatesAreRemovedSilentlyAndLabelsAreNormalized() throws Exception {
		long show = newShow(2, 100, "A1", "A2");

		reserve(newUser(), show, "[\"a1\",\"A1\",\"a1\",\"a2\"]", "k1", null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats.length()").value(2))
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.seats[1]").value("A2"))
				.andExpect(jsonPath("$.amount_paise").value(200));
	}

	@Test
	void userIdInTheBodyIsIgnored() throws Exception {
		long show = newShow(4, 100, "A1");
		String real = newUser();
		String claimed = newUser();

		reserve(auth(real), show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\",\"user_id\":\"" + claimed + "\"}")
				.andExpect(status().isCreated());

		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ? AND user_id = ?", show, real)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE user_id = ?", claimed)).isZero();
	}

	// ---- booking ----

	@Test
	void allOrNothingBooksEverySeatAndWritesConsistentRows() throws Exception {
		long show = newShow(4, 25000, "A1", "A2", "A3");
		String user = newUser();

		ResultActions result = reserve(user, show, "[\"A2\",\"A1\"]", "k1", "all_or_nothing")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.show_id").value(show))
				.andExpect(jsonPath("$.status").value("confirmed"))
				.andExpect(jsonPath("$.amount_paise").value(50000))
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.seats[1]").value("A2"))
				.andExpect(jsonPath("$.unavailable_seats").doesNotExist());
		String id = reservationId(result);

		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A2")).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A3")).isEqualTo("available");
		assertThat(jdbc.queryForList("SELECT seat_label FROM reservation_seats WHERE reservation_id = ? ORDER BY seat_label", String.class, id))
				.containsExactly("A1", "A2");
		assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, id)).isEqualTo("confirmed");
		assertThat(jdbc.queryForObject("SELECT amount_paise FROM reservations WHERE id = ?", Long.class, id)).isEqualTo(50000L);
		assertThat(heldCount(user, show)).isEqualTo(2);
		assertThat(keys(user, "k1")).isEqualTo(1);
		assertInvariants(show);
	}

	@Test
	void showViewReflectsTheBooking() throws Exception {
		long show = newShow(4, 100, "A1", "A2", "A3");
		reserve(newUser(), show, "[\"A1\"]", "k1", null).andExpect(status().isCreated());

		mockMvc.perform(get("/shows/" + show).header("Authorization", ADMIN))
				.andExpect(jsonPath("$.counts.available").value(2))
				.andExpect(jsonPath("$.counts.confirmed").value(1))
				.andExpect(jsonPath("$.seats[0].status").value("confirmed"));
	}

	@Test
	void modeDefaultsToAllOrNothing() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A1'", show);

		reserve(newUser(), show, "[\"A1\",\"A2\"]", "k1", null)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("seat-taken"));
		assertThat(seatStatus(show, "A2")).isEqualTo("available");
	}

	@Test
	void allOrNothingDeclineRollsBackEverythingAndStoresNothing() throws Exception {
		long show = newShow(4, 100, "A1", "A2", "A3");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);
		String user = newUser();

		reserve(user, show, "[\"A1\",\"A2\",\"A3\"]", "k1", "all_or_nothing")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("seat-taken"))
				.andExpect(jsonPath("$.message").value("Seat(s) not available: A2"));

		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		assertThat(seatStatus(show, "A3")).isEqualTo("available");
		assertThat(reservations(show)).isZero();
		assertThat(keys(user, "k1")).isZero();
		assertThat(heldCount(user, show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", show)).isEqualTo(1);
	}

	@Test
	void unknownLabelsCountAsUnavailable() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String user = newUser();

		reserve(user, show, "[\"A1\",\"ZZ9\"]", "k1", "all_or_nothing")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("seat-taken"));
		assertThat(seatStatus(show, "A1")).isEqualTo("available");

		reserve(user, show, "[\"A1\",\"ZZ9\"]", "k2", "best_effort")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.unavailable_seats[0]").value("ZZ9"));
		assertInvariants(show);
	}

	@Test
	void bestEffortBooksTheFreeSeatsAndGivesTheUnusedQuotaBack() throws Exception {
		long show = newShow(4, 1000, "A1", "A2", "A3", "A4");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label IN ('A2', 'A4')", show);
		String user = newUser();
		double partialBefore = counter("reservations.partial");

		reserve(user, show, "[\"A1\",\"A2\",\"A3\",\"A4\"]", "k1", "best_effort")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("confirmed"))
				.andExpect(jsonPath("$.amount_paise").value(2000))
				.andExpect(jsonPath("$.seats.length()").value(2))
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.seats[1]").value("A3"))
				.andExpect(jsonPath("$.unavailable_seats.length()").value(2))
				.andExpect(jsonPath("$.unavailable_seats[0]").value("A2"))
				.andExpect(jsonPath("$.unavailable_seats[1]").value("A4"));

		assertThat(heldCount(user, show)).isEqualTo(2);
		assertThat(counter("reservations.partial")).isEqualTo(partialBefore + 1);
		// A2 and A4 were confirmed by hand in this test (no reservation_seats rows), so check this booking only.
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ? AND status = 'pending'", show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isEqualTo(2);
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A3")).isEqualTo("confirmed");
	}

	@Test
	void bestEffortThatBooksNothingIs409AndStoresNothing() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ?", show);
		String user = newUser();

		reserve(user, show, "[\"A1\",\"A2\"]", "k1", "best_effort")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("seat-taken"));

		assertThat(reservations(show)).isZero();
		assertThat(keys(user, "k1")).isZero();
		assertThat(heldCount(user, show)).isZero();
	}

	@Test
	void aFullyAvailableBestEffortRequestHasNoUnavailableList() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		double partialBefore = counter("reservations.partial");

		reserve(newUser(), show, "[\"A1\",\"A2\"]", "k1", "best_effort")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.unavailable_seats").doesNotExist());
		assertThat(counter("reservations.partial")).isEqualTo(partialBefore);
	}

	// ---- per-user limit ----

	@Test
	void limitIsEnforcedAcrossRequestsAndDeclinesBookNothing() throws Exception {
		long show = newShow(2, 100, "A1", "A2", "A3", "A4");
		String user = newUser();

		reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated());
		reserve(user, show, "[\"A2\"]", "k2", null).andExpect(status().isCreated());
		reserve(user, show, "[\"A3\"]", "k3", null)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("per-user-limit"));

		assertThat(seatStatus(show, "A3")).isEqualTo("available");
		assertThat(heldCount(user, show)).isEqualTo(2);
		assertThat(keys(user, "k3")).isZero();
		assertInvariants(show);
	}

	@Test
	void aRequestThatWouldCrossTheLimitIsDeclinedAsAWhole() throws Exception {
		long show = newShow(2, 100, "A1", "A2", "A3");
		String user = newUser();
		reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated());

		reserve(user, show, "[\"A2\",\"A3\"]", "k2", "best_effort")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("per-user-limit"));

		assertThat(seatStatus(show, "A2")).isEqualTo("available");
		assertThat(heldCount(user, show)).isEqualTo(1);
	}

	@Test
	void limitIsPerUserAndPerShow() throws Exception {
		long showOne = newShow(1, 100, "A1", "A2");
		long showTwo = newShow(1, 100, "A1");
		String alice = newUser();
		String bob = newUser();

		reserve(alice, showOne, "[\"A1\"]", "k1", null).andExpect(status().isCreated());
		reserve(alice, showTwo, "[\"A1\"]", "k2", null).andExpect(status().isCreated());
		reserve(bob, showOne, "[\"A2\"]", "k3", null).andExpect(status().isCreated());
		reserve(alice, showOne, "[\"A2\"]", "k4", null).andExpect(status().isConflict());
	}

	// ---- idempotency ----

	@Test
	void sameKeyAndSameRequestReplaysTheOriginalReservation() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String user = newUser();
		double confirmedBefore = counter("reservations.confirmed");
		double replayBefore = counter("reservations.declined", "reason", "idempotent-replay");

		String id = reservationId(reserve(user, show, "[\"A1\",\"A2\"]", "k1", null).andExpect(status().isCreated()));
		reserve(user, show, "[\"A2\",\"a1\"]", "k1", null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reservation_id").value(id))
				.andExpect(jsonPath("$.status").value("confirmed"))
				.andExpect(jsonPath("$.seats.length()").value(2))
				.andExpect(jsonPath("$.amount_paise").value(200));

		assertThat(reservations(show)).isEqualTo(1);
		assertThat(heldCount(user, show)).isEqualTo(2);
		assertThat(counter("reservations.confirmed")).isEqualTo(confirmedBefore + 1);
		assertThat(counter("reservations.declined", "reason", "idempotent-replay")).isEqualTo(replayBefore + 1);
		assertInvariants(show);
	}

	@Test
	void replayOfABestEffortRequestStillListsTheMissedSeats() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);
		String user = newUser();

		reserve(user, show, "[\"A1\",\"A2\"]", "k1", "best_effort").andExpect(status().isCreated());
		reserve(user, show, "[\"A1\",\"A2\"]", "k1", "best_effort")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.unavailable_seats[0]").value("A2"));
	}

	@Test
	void sameKeyWithADifferentRequestIsAConflict() throws Exception {
		long show = newShow(4, 100, "A1", "A2", "A3");
		String user = newUser();
		reserve(user, show, "[\"A1\"]", "k1", "all_or_nothing").andExpect(status().isCreated());
		double conflictsBefore = counter("reservations.declined", "reason", "idempotency-conflict");

		reserve(user, show, "[\"A2\"]", "k1", "all_or_nothing")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("idempotency-conflict"));
		reserve(user, show, "[\"A1\"]", "k1", "best_effort")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("idempotency-conflict"));

		assertThat(seatStatus(show, "A2")).isEqualTo("available");
		assertThat(reservations(show)).isEqualTo(1);
		assertThat(counter("reservations.declined", "reason", "idempotency-conflict")).isEqualTo(conflictsBefore + 2);
	}

	@Test
	void sameKeyOnAnotherShowIsAConflict() throws Exception {
		long showOne = newShow(4, 100, "A1");
		long showTwo = newShow(4, 100, "A1");
		String user = newUser();
		reserve(user, showOne, "[\"A1\"]", "k1", null).andExpect(status().isCreated());

		reserve(user, showTwo, "[\"A1\"]", "k1", null)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("idempotency-conflict"));
		assertThat(seatStatus(showTwo, "A1")).isEqualTo("available");
	}

	@Test
	void theSameKeyFromAnotherUserIsIndependent() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String alice = newUser();
		String bob = newUser();

		String first = reservationId(reserve(alice, show, "[\"A1\"]", "shared", null).andExpect(status().isCreated()));
		String second = reservationId(reserve(bob, show, "[\"A2\"]", "shared", null).andExpect(status().isCreated()));

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void declinedRequestsStoreNoKeySoTheSameKeyWorksOnceTheSeatIsFree() throws Exception {
		long show = newShow(4, 100, "A1");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ?", show);
		String user = newUser();

		reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isConflict());
		jdbc.update("UPDATE seats SET status = 'available' WHERE show_id = ?", show);

		reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated());
		assertThat(keys(user, "k1")).isEqualTo(1);
	}

	@Test
	void replayOfACancelledReservationReturnsItAsCancelledWithNoSeats() throws Exception {
		long show = newShow(4, 100, "A1");
		String user = newUser();
		String id = reservationId(reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated()));
		jdbc.update("UPDATE reservations SET status = 'cancelled' WHERE id = ?", id);
		jdbc.update("DELETE FROM reservation_seats WHERE reservation_id = ?", id);

		reserve(user, show, "[\"A1\"]", "k1", null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reservation_id").value(id))
				.andExpect(jsonPath("$.status").value("cancelled"))
				.andExpect(jsonPath("$.seats.length()").value(0));
	}

	@Test
	void aReplayThatMissesTheFastPathRollsBackItsProvisionalReservationAndStillReturns201() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String user = newUser();
		String id = reservationId(reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated()));

		// Two copies racing: this one did both fast-path reads before the first copy committed, so it saw the seat free
		// and no key. It goes into the transaction and meets the existing key there.
		doReturn(Set.of("A1")).doCallRealMethod().when(seatStore).findAvailableSeatLabels(anyLong(), anyCollection());
		doReturn(Optional.empty()).doCallRealMethod().when(seatStore).findIdempotencyKey(anyString(), anyString());
		reserve(user, show, "[\"A1\"]", "k1", null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reservation_id").value(id));

		assertThat(reservations(show)).isEqualTo(1);
		assertThat(heldCount(user, show)).isEqualTo(1);
		assertInvariants(show);
	}

	@Test
	void theTransactionReportsAnExistingKeyAndLeavesNoPendingRowBehind() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		String user = newUser();
		String id = reservationId(reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated()));
		String hash = jdbc.queryForObject("SELECT request_hash FROM idempotency_keys WHERE user_id = ? AND idem_key = 'k1'", String.class, user);

		var outcome = reserveTransaction.reserveOnce(new Attempt(user, show, 100, 4, List.of("A2"), "k1", "other-hash",
				ReservationMode.all_or_nothing));

		assertThat(outcome).isInstanceOf(KeyExists.class);
		assertThat(((KeyExists) outcome).reservationId()).isEqualTo(id);
		assertThat(((KeyExists) outcome).storedRequestHash()).isEqualTo(hash);
		assertThat(reservations(show)).isEqualTo(1);
		assertThat(seatStatus(show, "A2")).isEqualTo("available");
		assertInvariants(show);
	}

	// ---- early refusal (the fast-path seat read) ----

	@Test
	void aTakenSeatIsRefusedBeforeAnythingIsWritten() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);
		String user = newUser();
		double seatTakenBefore = counter("reservations.declined", "reason", "seat-taken");

		reserve(user, show, "[\"A1\",\"A2\"]", "k1", "all_or_nothing")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("seat-taken"))
				.andExpect(jsonPath("$.message").value("Seat(s) not available: A2"));
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ?", show);
		reserve(user, show, "[\"A1\",\"A2\"]", "k2", "best_effort")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("seat-taken"))
				.andExpect(jsonPath("$.message").value("Seat(s) not available: A1, A2"));

		// No transaction was started: not even the provisional reservation was inserted.
		verify(seatStore, never()).insertPendingReservation(anyString(), anyLong(), anyString());
		assertThat(counter("reservations.declined", "reason", "seat-taken")).isEqualTo(seatTakenBefore + 2);
		assertThat(reservations(show)).isZero();
		assertThat(heldCount(user, show)).isZero();
	}

	@Test
	void aBestEffortRequestWithOneFreeSeatStillGoesIntoTheTransaction() throws Exception {
		long show = newShow(4, 100, "A1", "A2");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);

		reserve(newUser(), show, "[\"A1\",\"A2\"]", "k1", "best_effort")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.unavailable_seats[0]").value("A2"));
	}

	@Test
	void aSeatThatOnlyLookedFreeIsStillDecidedByTheGuardedUpdate() throws Exception {
		long show = newShow(4, 100, "A1");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ?", show);
		String user = newUser();

		// The early read is stale: it says A1 is free although it was taken a moment ago.
		doReturn(Set.of("A1")).when(seatStore).findAvailableSeatLabels(anyLong(), anyCollection());
		reserve(user, show, "[\"A1\"]", "k1", "all_or_nothing")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("seat-taken"));
		reserve(user, show, "[\"A1\"]", "k2", "best_effort")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("seat-taken"));

		verify(seatStore).claimSeats(show, List.of("A1"));
		verify(seatStore).claimSeat(show, "A1");
		assertThat(reservations(show)).isZero();
		assertThat(keys(user, "k1")).isZero();
		assertThat(heldCount(user, show)).isZero();
	}

	@Test
	void allOrNothingClaimsEverySeatInOneStatement() throws Exception {
		long show = newShow(4, 100, "A1", "A2", "A3");

		reserve(newUser(), show, "[\"A3\",\"A1\",\"A2\"]", "k1", "all_or_nothing")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats.length()").value(3))
				.andExpect(jsonPath("$.amount_paise").value(300));

		verify(seatStore).claimSeats(show, List.of("A1", "A2", "A3"));
		verify(seatStore, never()).claimSeat(anyLong(), anyString());
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isEqualTo(3);
		assertInvariants(show);
	}

	@Test
	void allOrNothingThatLosesOneSeatInsideTheTransactionGivesTheOthersBack() throws Exception {
		long show = newShow(4, 100, "A1", "A2", "A3");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);
		String user = newUser();

		// The early read is stale (A2 looked free), so the one-statement claim wins only 2 of 3 and must roll back.
		doReturn(Set.of("A1", "A2", "A3")).when(seatStore).findAvailableSeatLabels(anyLong(), anyCollection());
		reserve(user, show, "[\"A1\",\"A2\",\"A3\"]", "k1", "all_or_nothing")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("seat-taken"))
				.andExpect(jsonPath("$.message").value("Seat(s) not available: A1, A2, A3"));

		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		assertThat(seatStatus(show, "A3")).isEqualTo("available");
		assertThat(reservations(show)).isZero();
		assertThat(keys(user, "k1")).isZero();
		assertThat(heldCount(user, show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isZero();
	}

	@Test
	void theSeatsAreReadBeforeTheKeySoAReplayIsNeverMistakenForATakenSeat() throws Exception {
		long show = newShow(4, 100, "A1");
		String user = newUser();
		String id = reservationId(reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated()));
		clearInvocations(seatStore);

		// A1 is taken now, by this very reservation. The key decides: 201 with the original reservation.
		reserve(user, show, "[\"A1\"]", "k1", null)
				.andExpect(status().isCreated()).andExpect(jsonPath("$.reservation_id").value(id));

		InOrder order = inOrder(seatStore);
		order.verify(seatStore).findAvailableSeatLabels(anyLong(), anyCollection());
		order.verify(seatStore).findIdempotencyKey(user, "k1");
		verify(seatStore, never()).insertPendingReservation(anyString(), anyLong(), anyString());
	}

	@Test
	void aTakenSeatIsReportedBeforeTheLimit() throws Exception {
		long show = newShow(1, 100, "A1", "A2");
		String user = newUser();
		reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated());
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);

		// Over the limit AND aimed at a taken seat: the seat is checked first, without a transaction.
		reserve(user, show, "[\"A2\"]", "k2", null)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("seat-taken"));
		assertThat(heldCount(user, show)).isEqualTo(1);
	}

	// ---- metrics and invariants over a mixed run ----

	@Test
	void declineReasonsAreCounted() throws Exception {
		long show = newShow(1, 100, "A1", "A2");
		String user = newUser();
		double seatTakenBefore = counter("reservations.declined", "reason", "seat-taken");
		double limitBefore = counter("reservations.declined", "reason", "per-user-limit");
		reserve(user, show, "[\"A1\"]", "k1", null).andExpect(status().isCreated());

		reserve(user, show, "[\"A2\"]", "k2", null).andExpect(status().isConflict());
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A2'", show);
		reserve(newUser(), show, "[\"A2\"]", "k3", null).andExpect(status().isConflict());

		assertThat(counter("reservations.declined", "reason", "per-user-limit")).isEqualTo(limitBefore + 1);
		assertThat(counter("reservations.declined", "reason", "seat-taken")).isEqualTo(seatTakenBefore + 1);
	}

	@Test
	void invariantsHoldAfterAMixedSequence() throws Exception {
		long show = newShow(3, 500, "A1", "A2", "A3", "A4", "A5", "A6");
		String alice = newUser();
		String bob = newUser();

		reserve(alice, show, "[\"A1\",\"A2\"]", "a1", "all_or_nothing").andExpect(status().isCreated());
		reserve(bob, show, "[\"A2\",\"A3\"]", "b1", "all_or_nothing").andExpect(status().isConflict());
		reserve(bob, show, "[\"A2\",\"A3\",\"A4\"]", "b2", "best_effort").andExpect(status().isCreated());
		reserve(alice, show, "[\"A5\",\"A6\"]", "a2", "all_or_nothing").andExpect(status().isConflict());
		reserve(alice, show, "[\"A5\"]", "a3", "all_or_nothing").andExpect(status().isCreated());
		reserve(alice, show, "[\"A1\",\"A2\"]", "a1", "all_or_nothing").andExpect(status().isCreated());

		assertInvariants(show);
		assertThat(heldCount(alice, show)).isEqualTo(3);
		assertThat(heldCount(bob, show)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", show)).isEqualTo(5);
		assertThat(count("SELECT COALESCE(SUM(held_count), 0) FROM user_show_counts WHERE show_id = ?", show)).isEqualTo(5);
	}

}
