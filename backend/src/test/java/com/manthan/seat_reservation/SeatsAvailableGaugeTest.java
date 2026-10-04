package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import com.manthan.seat_reservation.observability.SeatsAvailableGauge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * The seats_available metric must agree with GET /shows/{id}. Here the test asks for a refresh directly. That the
 * metrics endpoint does it by itself on every request is checked in ActuatorAccessTest. Not @Transactional: bookings really commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeatsAvailableGaugeTest {

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
	SeatsAvailableGauge seatsAvailable;

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
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, 100, 4, ?)", "t-" + UUID.randomUUID(), seats.length);
		long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		showIds.add(id);
		for (String seat : seats) {
			jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, ?)", id, seat);
		}
		return id;
	}

	private String newUserAuth() {
		String id = UUID.randomUUID().toString();
		userIds.add(id);
		return "Bearer " + tokenService.issue(id, tokenService.expiryFromNow());
	}

	/** Returns the reservation id, or null when the request was declined. */
	private String reserve(String auth, long show, String seatsJson, String key) throws Exception {
		String body = mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}")).andReturn().getResponse().getContentAsString();
		var id = json.readTree(body).get("reservation_id");
		return id == null ? null : id.asString();
	}

	/** The metric value for one show after a refresh, or null if the show has no metric. */
	private Double metric(long show) {
		seatsAvailable.refresh();
		Gauge gauge = meters.find("seats.available").tag("show_id", String.valueOf(show)).gauge();
		return gauge == null ? null : gauge.value();
	}

	private int availableInShowView(long show) throws Exception {
		String body = mockMvc.perform(get("/shows/" + show).header("Authorization", ADMIN)).andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("counts").get("available").asInt();
	}

	@Test
	void theMetricFollowsBookingsAndCancelsAndAlwaysMatchesTheShowView() throws Exception {
		long show = newShow("A1", "A2", "A3", "A4", "A5");
		String user = newUserAuth();

		assertThat(metric(show)).isEqualTo(5.0);
		assertThat(availableInShowView(show)).isEqualTo(5);

		String reservation = reserve(user, show, "[\"A1\",\"A2\"]", "k1");
		assertThat(metric(show)).isEqualTo(3.0);
		assertThat(availableInShowView(show)).isEqualTo(3);

		// A declined request changes nothing.
		reserve(newUserAuth(), show, "[\"A2\",\"A3\"]", "k2");
		assertThat(metric(show)).isEqualTo(3.0);

		mockMvc.perform(post("/reservations/" + reservation + "/cancel").header("Authorization", user));
		assertThat(metric(show)).isEqualTo(5.0);
		assertThat(availableInShowView(show)).isEqualTo(5);
	}

	@Test
	void aSoldOutShowReportsZeroInsteadOfDisappearing() throws Exception {
		long show = newShow("A1", "A2");

		reserve(newUserAuth(), show, "[\"A1\",\"A2\"]", "k1");

		assertThat(metric(show)).isEqualTo(0.0);
		assertThat(availableInShowView(show)).isZero();
	}

	@Test
	void eachShowHasItsOwnValue() throws Exception {
		long first = newShow("A1", "A2", "A3");
		long second = newShow("A1");
		reserve(newUserAuth(), first, "[\"A1\"]", "k1");

		assertThat(metric(first)).isEqualTo(2.0);
		assertThat(metric(second)).isEqualTo(1.0);
	}

	@Test
	void aShowCreatedThroughTheApiGetsAMetricOnTheNextRefresh() throws Exception {
		String body = mockMvc.perform(post("/shows").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"gauge-" + UUID.randomUUID() + "\",\"seats\":[\"A1\",\"A2\",\"A3\"],\"price_paise\":100}"))
				.andReturn().getResponse().getContentAsString();
		long show = json.readTree(body).get("id").asLong();
		showIds.add(show);

		assertThat(metric(show)).isEqualTo(3.0);
	}

}
