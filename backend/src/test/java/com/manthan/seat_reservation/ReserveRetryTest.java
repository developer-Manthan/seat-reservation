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
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

import com.manthan.seat_reservation.auth.TokenService;
import com.manthan.seat_reservation.service.ReserveTransaction;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Retry and 429 behaviour through the real endpoint. The transactional attempt is a spy that throws lock failures a
 * set number of times, then runs for real. Backoff is shrunk to 1-2 ms so the tests stay fast. Not @Transactional.
 */
@SpringBootTest(properties = { "app.reservation.retry.base-backoff-ms=1", "app.reservation.retry.max-backoff-ms=2" })
@AutoConfigureMockMvc
class ReserveRetryTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	MeterRegistry meters;

	@MockitoSpyBean
	ReserveTransaction reserveTransaction;

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

	private long newShow(String... seats) {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, 100, 4, ?)",
				"t-" + UUID.randomUUID(), seats.length);
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

	private ResultActions reserve(String auth, long show, String seatsJson, String key) throws Exception {
		return mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}"));
	}

	private static RuntimeException deadlock() {
		return new DeadlockLoserDataAccessException("Deadlock found", new SQLException("Deadlock found", "40001", 1213));
	}

	private static RuntimeException lockTimeout() {
		return new CannotAcquireLockException("Lock wait timeout", new SQLException("Lock wait timeout exceeded", "HY000", 1205));
	}

	private double counter(String name) {
		var counter = meters.find(name).counter();
		return counter == null ? 0 : counter.count();
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void twoDeadlocksThenSuccessStillBooksOnceAndReturns201() throws Exception {
		long show = newShow("A1", "A2");
		double retriesBefore = counter("reservations.retries");
		doThrow(deadlock()).doThrow(deadlock()).doCallRealMethod().when(reserveTransaction).reserveOnce(any());

		reserve(newUserAuth(), show, "[\"A1\"]", "k1")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("confirmed"))
				.andExpect(jsonPath("$.seats[0]").value("A1"));

		verify(reserveTransaction, times(3)).reserveOnce(any());
		assertThat(counter("reservations.retries")).isEqualTo(retriesBefore + 2);
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ?", show)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ? AND status = 'pending'", show)).isZero();
	}

	@Test
	void lockWaitTimeoutsAreRetriedToo() throws Exception {
		long show = newShow("A1");
		doThrow(lockTimeout()).doCallRealMethod().when(reserveTransaction).reserveOnce(any());

		reserve(newUserAuth(), show, "[\"A1\"]", "k1").andExpect(status().isCreated());

		verify(reserveTransaction, times(2)).reserveOnce(any());
	}

	@Test
	void exhaustedRetriesAnswer429WithRetryAfterAndStoreNothing() throws Exception {
		long show = newShow("A1");
		double retriesBefore = counter("reservations.retries");
		double throttledBefore = counter("requests.throttled");
		doThrow(deadlock()).when(reserveTransaction).reserveOnce(any());

		reserve(newUserAuth(), show, "[\"A1\"]", "k1")
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "1"))
				.andExpect(jsonPath("$.error").value("too-many-requests"))
				.andExpect(jsonPath("$.message").exists());

		verify(reserveTransaction, times(5)).reserveOnce(any());
		assertThat(counter("reservations.retries")).isEqualTo(retriesBefore + 4);
		assertThat(counter("requests.throttled")).isEqualTo(throttledBefore + 1);
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ?", show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", show)).isEqualTo(1);
	}

	@Test
	void aDomainDeclineIsNotRetried() throws Exception {
		long show = newShow("A1");
		jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ?", show);
		doCallRealMethod().when(reserveTransaction).reserveOnce(any());

		reserve(newUserAuth(), show, "[\"A1\"]", "k1")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("seat-taken"));

		verify(reserveTransaction, times(1)).reserveOnce(any());
	}

	@Test
	void anUnexpectedErrorIsNotRetriedAndIsNeverA429() throws Exception {
		long show = newShow("A1");
		doThrow(new IllegalStateException("bug")).when(reserveTransaction).reserveOnce(any());

		reserve(newUserAuth(), show, "[\"A1\"]", "k1").andExpect(status().isInternalServerError());

		verify(reserveTransaction, times(1)).reserveOnce(any());
	}

	@Test
	void aRetriedRequestWithTheSameKeyStillReplaysCleanlyAfterwards() throws Exception {
		long show = newShow("A1", "A2");
		String auth = newUserAuth();
		doThrow(deadlock()).doCallRealMethod().when(reserveTransaction).reserveOnce(any());
		String body = reserve(auth, show, "[\"A1\"]", "k1").andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String reservationId = body.replaceAll(".*\"reservation_id\":\"([^\"]+)\".*", "$1");

		reserve(auth, show, "[\"A1\"]", "k1")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.reservation_id").value(reservationId));

		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ?", show)).isEqualTo(1);
	}

}
