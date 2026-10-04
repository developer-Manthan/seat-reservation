package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import com.manthan.seat_reservation.auth.TokenService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The read-only lists behind the UI (GET /users, GET /shows, GET /reservations) and the page itself.
 * Not @Transactional: bookings really commit. Each test removes what it created.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrowseTest {

	private static final String ADMIN = "Bearer dev-only-admin-token";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	ObjectMapper json;

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
		userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
	}

	private long newShow(String... seats) {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, 250, 4, ?)", "t-" + UUID.randomUUID(), seats.length);
		long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		showIds.add(id);
		for (String seat : seats) {
			jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, ?)", id, seat);
		}
		return id;
	}

	private String newUserId() {
		String id = UUID.randomUUID().toString();
		userIds.add(id);
		return id;
	}

	private String auth(String userId) {
		return "Bearer " + tokenService.issue(userId, tokenService.expiryFromNow());
	}

	private String book(String userId, long show, String seatsJson, String key) throws Exception {
		String body = mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth(userId))
						.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("reservation_id").asString();
	}

	private JsonNode getJson(String path, String authorization) throws Exception {
		var request = get(path);
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return json.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	// ---- the page ----

	@Test
	void thePageIsServedWithoutAToken() throws Exception {
		for (String path : new String[] { "/", "/index.html" }) {
			mockMvc.perform(get(path)).andExpect(status().isOk());
		}
		mockMvc.perform(get("/index.html")).andExpect(content().string(containsString("<title>Seat Reservation</title>")));
	}

	@Test
	void otherPathsAreStillProtected() throws Exception {
		for (String path : new String[] { "/shows", "/reservations", "/other.html", "/static/index.html" }) {
			mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
		}
	}

	// ---- GET /users ----

	@Test
	void usersAreListedWithoutATokenNewestFirst() throws Exception {
		String older = newUserId();
		String newer = newUserId();
		jdbc.update("INSERT INTO users (id, created_at) VALUES (?, '2020-01-01 00:00:00')", older);
		mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + newer + "\"}"))
				.andExpect(status().isOk());

		JsonNode users = getJson("/users", null);

		List<String> ids = new ArrayList<>();
		users.forEach(u -> ids.add(u.get("user_id").asString()));
		assertThat(ids).contains(older, newer);
		assertThat(ids.indexOf(newer)).isLessThan(ids.indexOf(older));
		assertThat(users.get(0).get("created_at").asString()).isNotBlank();
		assertThat(users.size()).isLessThanOrEqualTo(200);
	}

	// ---- GET /shows ----

	@Test
	void showsAreListedWithTheirAvailableSeats() throws Exception {
		long first = newShow("A1", "A2", "A3");
		long second = newShow("A1");
		String user = newUserId();
		book(user, first, "[\"A1\",\"A2\"]", "k1");
		book(user, second, "[\"A1\"]", "k2");

		JsonNode shows = getJson("/shows", auth(user));

		assertThat(shows.get(0).get("id").asLong()).as("newest first").isEqualTo(second);
		assertThat(shows.get(0).get("available").asInt()).as("sold out is listed with 0").isZero();
		JsonNode firstShow = shows.get(1);
		assertThat(firstShow.get("id").asLong()).isEqualTo(first);
		assertThat(firstShow.get("available").asInt()).isEqualTo(1);
		assertThat(firstShow.get("total_seats").asInt()).isEqualTo(3);
		assertThat(firstShow.get("price_paise").asLong()).isEqualTo(250);
		assertThat(firstShow.get("per_user_limit").asInt()).isEqualTo(4);
		assertThat(firstShow.get("name").asString()).startsWith("t-");
	}

	@Test
	void theAdminTokenCanListShowsToo() throws Exception {
		newShow("A1");

		assertThat(getJson("/shows", ADMIN).size()).isGreaterThanOrEqualTo(1);
	}

	// ---- GET /reservations ----

	@Test
	void reservationsNeedAUserToken() throws Exception {
		mockMvc.perform(get("/reservations")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/reservations").header("Authorization", ADMIN)).andExpect(status().isForbidden());
	}

	@Test
	void aUserSeesOnlyTheirOwnReservationsWithSeatsInNaturalOrder() throws Exception {
		long show = newShow("A1", "A2", "A10", "B1");
		String alice = newUserId();
		String bob = newUserId();
		String aliceRes = book(alice, show, "[\"A10\",\"A2\",\"A1\"]", "a1");
		book(bob, show, "[\"B1\"]", "b1");

		JsonNode mine = getJson("/reservations", auth(alice));

		assertThat(mine.size()).isEqualTo(1);
		JsonNode reservation = mine.get(0);
		assertThat(reservation.get("reservation_id").asString()).isEqualTo(aliceRes);
		assertThat(reservation.get("show_id").asLong()).isEqualTo(show);
		assertThat(reservation.get("status").asString()).isEqualTo("confirmed");
		assertThat(reservation.get("amount_paise").asLong()).isEqualTo(750);
		List<String> seats = new ArrayList<>();
		reservation.get("seats").forEach(s -> seats.add(s.asString()));
		assertThat(seats).containsExactly("A1", "A2", "A10");
		assertThat(reservation.get("created_at").asString()).isNotBlank();
	}

	@Test
	void aUserIdInTheQueryStringIsIgnored() throws Exception {
		long show = newShow("A1", "A2");
		String alice = newUserId();
		String bob = newUserId();
		book(bob, show, "[\"A1\"]", "b1");

		JsonNode mine = getJson("/reservations?user_id=" + bob, auth(alice));

		assertThat(mine.size()).as("identity comes from the token only").isZero();
	}

	@Test
	void reservationsCanBeFilteredByShow() throws Exception {
		long first = newShow("A1");
		long second = newShow("A1");
		String user = newUserId();
		book(user, first, "[\"A1\"]", "k1");
		String inSecond = book(user, second, "[\"A1\"]", "k2");

		assertThat(getJson("/reservations", auth(user)).size()).isEqualTo(2);
		JsonNode filtered = getJson("/reservations?show_id=" + second, auth(user));
		assertThat(filtered.size()).isEqualTo(1);
		assertThat(filtered.get(0).get("reservation_id").asString()).isEqualTo(inSecond);
		mockMvc.perform(get("/reservations?show_id=abc").header("Authorization", auth(user))).andExpect(status().isBadRequest());
	}

	@Test
	void aCancelledReservationStaysInTheListAsCancelledWithNoSeats() throws Exception {
		long show = newShow("A1", "A2");
		String user = newUserId();
		String reservation = book(user, show, "[\"A1\",\"A2\"]", "k1");
		mockMvc.perform(post("/reservations/" + reservation + "/cancel").header("Authorization", auth(user))).andExpect(status().isOk());

		JsonNode mine = getJson("/reservations?show_id=" + show, auth(user));

		assertThat(mine.size()).isEqualTo(1);
		assertThat(mine.get(0).get("status").asString()).isEqualTo("cancelled");
		assertThat(mine.get(0).get("seats").size()).isZero();
	}

	@Test
	void aUserWithNoReservationsGetsAnEmptyList() throws Exception {
		mockMvc.perform(get("/reservations").header("Authorization", auth(newUserId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

}
