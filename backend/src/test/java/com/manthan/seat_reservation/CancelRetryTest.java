package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.manthan.seat_reservation.auth.TokenService;
import com.manthan.seat_reservation.service.CancelTransaction;

import io.micrometer.core.instrument.MeterRegistry;

/** Retry and 429 for cancel, through the real endpoint. The attempt is a spy that throws lock failures, then runs. */
@SpringBootTest(properties = { "app.reservation.retry.base-backoff-ms=1", "app.reservation.retry.max-backoff-ms=2" })
@AutoConfigureMockMvc
class CancelRetryTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	MeterRegistry meters;

	@MockitoSpyBean
	CancelTransaction cancelTransaction;

	private long showId;
	private String userId;
	private String reservationId;

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

	private String bookTwoSeats() throws Exception {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, 100, 4, 2)", "t-" + UUID.randomUUID());
		showId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'A1'), (?, 'A2')", showId, showId);
		userId = UUID.randomUUID().toString();
		String auth = "Bearer " + tokenService.issue(userId, tokenService.expiryFromNow());
		String body = mockMvc.perform(post("/shows/" + showId + "/reserve").header("Authorization", auth)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\",\"A2\"],\"idempotency_key\":\"k1\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		reservationId = body.replaceAll(".*\"reservation_id\":\"([^\"]+)\".*", "$1");
		return auth;
	}

	private static RuntimeException deadlock() {
		return new DeadlockLoserDataAccessException("Deadlock found", new SQLException("Deadlock found", "40001", 1213));
	}

	private double counter(String name) {
		var counter = meters.find(name).counter();
		return counter == null ? 0 : counter.count();
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void deadlocksAreRetriedAndTheCancelStillHappensExactlyOnce() throws Exception {
		String auth = bookTwoSeats();
		double retriesBefore = counter("reservations.retries");
		doThrow(deadlock()).doThrow(new CannotAcquireLockException("lock wait timeout", new SQLException("Lock wait timeout exceeded", "HY000", 1205)))
				.doCallRealMethod().when(cancelTransaction).cancelOnce(any(), any());

		mockMvc.perform(post("/reservations/" + reservationId + "/cancel").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("cancelled"));

		verify(cancelTransaction, times(3)).cancelOnce(any(), any());
		assertThat(counter("reservations.retries")).isEqualTo(retriesBefore + 2);
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", showId)).isEqualTo(2);
		assertThat(count("SELECT COALESCE(SUM(held_count), 0) FROM user_show_counts WHERE show_id = ?", showId)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", showId)).isZero();
	}

	@Test
	void exhaustedRetriesAre429WithRetryAfterAndNothingChanges() throws Exception {
		String auth = bookTwoSeats();
		double throttledBefore = counter("requests.throttled");
		doThrow(deadlock()).when(cancelTransaction).cancelOnce(any(), any());

		mockMvc.perform(post("/reservations/" + reservationId + "/cancel").header("Authorization", auth))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "1"))
				.andExpect(jsonPath("$.error").value("too-many-requests"));

		verify(cancelTransaction, times(5)).cancelOnce(any(), any());
		assertThat(counter("requests.throttled")).isEqualTo(throttledBefore + 1);
		assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId)).isEqualTo("confirmed");
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", showId)).isEqualTo(2);
	}

	@Test
	void aDeclinedCancelIsNotRetried() throws Exception {
		String auth = bookTwoSeats();
		doCallRealMethod().when(cancelTransaction).cancelOnce(any(), any());

		mockMvc.perform(post("/reservations/" + UUID.randomUUID() + "/cancel").header("Authorization", auth))
				.andExpect(status().isNotFound());

		verify(cancelTransaction, times(1)).cancelOnce(any(), any());
	}

}
