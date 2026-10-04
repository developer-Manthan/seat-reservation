package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.auth.TokenService;

/** GET /shows/{id} through the real auth interceptor. Rolls back, so nothing is left in the test schema. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShowReadTest {

	private static final String ADMIN = "Bearer dev-only-admin-token";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	private String userAuth() {
		return "Bearer " + tokenService.issue(UUID.randomUUID().toString(), tokenService.expiryFromNow());
	}

	private long createShow(String name, String seatsJson) throws Exception {
		String body = mockMvc.perform(post("/shows").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"" + name + "\",\"price_paise\":25000,\"seats\":" + seatsJson + "}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
	}

	private ResultActions read(String authorization, String path) throws Exception {
		var request = get(path);
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return mockMvc.perform(request);
	}

	private void setStatus(long showId, String label, String status) {
		jdbc.update("UPDATE seats SET status = ? WHERE show_id = ? AND seat_label = ?", status, showId, label);
	}

	// ---- auth ----

	@Test
	void noTokenIs401() throws Exception {
		long id = createShow("auth-none", "[\"A1\"]");

		read(null, "/shows/" + id).andExpect(status().isUnauthorized());
	}

	@Test
	void userAndAdminCanBothRead() throws Exception {
		long id = createShow("auth-both", "[\"A1\"]");

		read(userAuth(), "/shows/" + id).andExpect(status().isOk());
		read(ADMIN, "/shows/" + id).andExpect(status().isOk());
	}

	// ---- content ----

	@Test
	void returnsTheShowItsSeatsAndTheCounts() throws Exception {
		long id = createShow("details", "[\"A1\",\"A2\",\"A3\"]");

		read(userAuth(), "/shows/" + id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.name").value("details"))
				.andExpect(jsonPath("$.price_paise").value(25000))
				.andExpect(jsonPath("$.per_user_limit").value(4))
				.andExpect(jsonPath("$.total_seats").value(3))
				.andExpect(jsonPath("$.counts.available").value(3))
				.andExpect(jsonPath("$.counts.held").value(0))
				.andExpect(jsonPath("$.counts.confirmed").value(0))
				.andExpect(jsonPath("$.seats.length()").value(3))
				.andExpect(jsonPath("$.seats[*].status", everyItem(is("available"))));
	}

	@Test
	void countsFollowTheSeatStatuses() throws Exception {
		long id = createShow("counts", "[\"A1\",\"A2\",\"A3\",\"A4\",\"A5\"]");
		setStatus(id, "A1", "confirmed");
		setStatus(id, "A2", "confirmed");
		setStatus(id, "A3", "held");

		read(userAuth(), "/shows/" + id)
				.andExpect(jsonPath("$.counts.available").value(2))
				.andExpect(jsonPath("$.counts.held").value(1))
				.andExpect(jsonPath("$.counts.confirmed").value(2))
				.andExpect(jsonPath("$.seats[0].seat_label").value("A1"))
				.andExpect(jsonPath("$.seats[0].status").value("confirmed"))
				.andExpect(jsonPath("$.seats[2].status").value("held"))
				.andExpect(jsonPath("$.seats[4].status").value("available"));
	}

	@Test
	void invariantAvailablePlusHeldPlusConfirmedEqualsTotalSeats() throws Exception {
		long id = createShow("invariant", "[\"A1\",\"A2\",\"A3\",\"A4\"]");
		setStatus(id, "A1", "confirmed");
		setStatus(id, "A4", "held");

		String body = read(userAuth(), "/shows/" + id).andReturn().getResponse().getContentAsString();
		int available = Integer.parseInt(body.replaceAll(".*\"available\":(\\d+).*", "$1"));
		int held = Integer.parseInt(body.replaceAll(".*\"held\":(\\d+).*", "$1"));
		int confirmed = Integer.parseInt(body.replaceAll(".*\"confirmed\":(\\d+).*", "$1"));
		int total = Integer.parseInt(body.replaceAll(".*\"total_seats\":(\\d+).*", "$1"));
		assertThat(available + held + confirmed).isEqualTo(total);
	}

	@Test
	void seatsComeInNaturalOrderNotAlphabetical() throws Exception {
		long id = createShow("order", "[\"B1\",\"A10\",\"A2\",\"A1\",\"B10\",\"B2\"]");

		read(userAuth(), "/shows/" + id)
				.andExpect(jsonPath("$.seats[0].seat_label").value("A1"))
				.andExpect(jsonPath("$.seats[1].seat_label").value("A2"))
				.andExpect(jsonPath("$.seats[2].seat_label").value("A10"))
				.andExpect(jsonPath("$.seats[3].seat_label").value("B1"))
				.andExpect(jsonPath("$.seats[4].seat_label").value("B2"))
				.andExpect(jsonPath("$.seats[5].seat_label").value("B10"));
	}

	@Test
	void seatsOfOtherShowsAreNotIncluded() throws Exception {
		long first = createShow("first", "[\"A1\",\"A2\"]");
		createShow("second", "[\"A1\",\"A2\",\"A3\"]");

		read(userAuth(), "/shows/" + first)
				.andExpect(jsonPath("$.total_seats").value(2))
				.andExpect(jsonPath("$.seats.length()").value(2));
	}

	@Test
	void aMaximumSizeShowIsReadInFull() throws Exception {
		StringBuilder seats = new StringBuilder("[");
		for (int i = 1; i <= 5000; i++) {
			seats.append(i > 1 ? "," : "").append("\"S").append(i).append("\"");
		}
		long id = createShow("arena", seats.append("]").toString());

		read(userAuth(), "/shows/" + id)
				.andExpect(jsonPath("$.seats.length()").value(5000))
				.andExpect(jsonPath("$.counts.available").value(5000))
				.andExpect(jsonPath("$.seats[8].seat_label").value("S9"))
				.andExpect(jsonPath("$.seats[9].seat_label").value("S10"))
				.andExpect(jsonPath("$.seats[4999].seat_label").value("S5000"));
	}

	// ---- errors ----

	@Test
	void unknownShowIs404() throws Exception {
		read(userAuth(), "/shows/999999999")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("not-found"))
				.andExpect(jsonPath("$.message").value("No show with id 999999999"));
	}

	@Test
	void nonNumericIdIs400() throws Exception {
		read(userAuth(), "/shows/abc")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("bad-request"));
	}

}
