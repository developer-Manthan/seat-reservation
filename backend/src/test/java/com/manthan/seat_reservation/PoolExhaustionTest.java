package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.manthan.seat_reservation.auth.TokenService;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A pool of two connections with a 250 ms connection timeout. The test holds both connections, so every request
 * that needs the database runs into the pool timeout, which must be a 429 with Retry-After and never a 500.
 */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0",
		"spring.datasource.hikari.connection-timeout=250" })
@AutoConfigureMockMvc
class PoolExhaustionTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	MeterRegistry meters;

	private long showId;
	private String userId;

	@AfterEach
	void cleanUp() {
		if (showId != 0) {
			jdbc.update("DELETE FROM idempotency_keys WHERE reservation_id IN (SELECT id FROM reservations WHERE show_id = ?)", showId);
			jdbc.update("DELETE FROM reservation_seats WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM reservations WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM user_show_counts WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM seats WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM shows WHERE id = ?", showId);
		}
		if (userId != null) {
			jdbc.update("DELETE FROM users WHERE id = ?", userId);
		}
	}

	private double throttled() {
		var counter = meters.find("requests.throttled").counter();
		return counter == null ? 0 : counter.count();
	}

	@Test
	void anExhaustedPoolIs429WithRetryAfterOnEveryEndpointAndRecoversWhenConnectionsReturn() throws Exception {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, 100, 4, 1)", "t-" + UUID.randomUUID());
		showId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'A1')", showId);
		userId = UUID.randomUUID().toString();
		String userAuth = "Bearer " + tokenService.issue(userId, tokenService.expiryFromNow());
		String reserveBody = "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}";
		double throttledBefore = throttled();

		Connection first = dataSource.getConnection();
		Connection second = dataSource.getConnection();
		try {
			// The user token needs a connection even to be accepted (users insert-if-absent).
			mockMvc.perform(post("/shows/" + showId + "/reserve").header("Authorization", userAuth)
							.contentType(MediaType.APPLICATION_JSON).content(reserveBody))
					.andExpect(status().isTooManyRequests())
					.andExpect(header().string("Retry-After", "1"))
					.andExpect(jsonPath("$.error").value("too-many-requests"));

			// The admin token needs no connection to authenticate, so this one fails inside the service instead.
			mockMvc.perform(get("/shows/" + showId).header("Authorization", "Bearer dev-only-admin-token"))
					.andExpect(status().isTooManyRequests())
					.andExpect(header().string("Retry-After", "1"));
		}
		finally {
			first.close();
			second.close();
		}

		assertThat(throttled()).isEqualTo(throttledBefore + 2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE show_id = ?", Integer.class, showId)).isZero();

		mockMvc.perform(post("/shows/" + showId + "/reserve").header("Authorization", userAuth)
						.contentType(MediaType.APPLICATION_JSON).content(reserveBody))
				.andExpect(status().isCreated());
		mockMvc.perform(get("/shows/" + showId).header("Authorization", "Bearer dev-only-admin-token")).andExpect(status().isOk());
	}

}
