package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.manthan.seat_reservation.auth.TokenService;

/**
 * Concurrent cancels against a real MySQL. A bigger pool than the other tests, because many requests run at once.
 * Not @Transactional: every request commits or rolls back on its own connection.
 */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=20", "spring.datasource.hikari.minimum-idle=2" })
@AutoConfigureMockMvc
class CancelRaceTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

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

	private long newShow(int perUserLimit, int seatCount) {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, 100, ?, ?)",
				"t-" + UUID.randomUUID(), perUserLimit, seatCount);
		long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		showIds.add(id);
		for (int i = 1; i <= seatCount; i++) {
			jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, ?)", id, "S" + i);
		}
		return id;
	}

	private String newUserAuth() {
		String id = UUID.randomUUID().toString();
		userIds.add(id);
		return "Bearer " + tokenService.issue(id, tokenService.expiryFromNow());
	}

	private String book(String auth, long show, String seatsJson, String key) throws Exception {
		String body = mockMvc.perform(post("/shows/" + show + "/reserve").header("Authorization", auth)
						.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return body.replaceAll(".*\"reservation_id\":\"([^\"]+)\".*", "$1");
	}

	private List<Integer> runTogether(List<Callable<Integer>> tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void manyConcurrentCancelsOfTheSameReservationHaveExactlyOneWinner() throws Exception {
		long show = newShow(4, 6);
		String auth = newUserAuth();
		String id = book(auth, show, "[\"S1\",\"S2\",\"S3\"]", "k1");

		List<Callable<Integer>> cancels = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			cancels.add(() -> mockMvc.perform(post("/reservations/" + id + "/cancel").header("Authorization", auth))
					.andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = runTogether(cancels);

		assertThat(statuses.stream().filter(s -> s == 200).count()).as("statuses %s", statuses).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).as("statuses %s", statuses).isEqualTo(11);
		assertThat(statuses).noneMatch(s -> s >= 500);
		// The counter went down once, not twelve times, and the seats were released once.
		assertThat(count("SELECT COALESCE(SUM(held_count), 0) FROM user_show_counts WHERE show_id = ?", show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", show)).isEqualTo(6);
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, id)).isEqualTo("cancelled");
	}

	@Test
	void concurrentCancelsOfDifferentUsersReservationsAllSucceedAndReconcile() throws Exception {
		int users = 16;
		long show = newShow(2, users * 2);
		List<String> auths = new ArrayList<>();
		List<String> reservationIds = new ArrayList<>();
		for (int i = 0; i < users; i++) {
			String auth = newUserAuth();
			auths.add(auth);
			reservationIds.add(book(auth, show, "[\"S" + (i * 2 + 1) + "\",\"S" + (i * 2 + 2) + "\"]", "k" + i));
		}
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", show)).isEqualTo(users * 2);

		List<Callable<Integer>> cancels = new ArrayList<>();
		for (int i = 0; i < users; i++) {
			String auth = auths.get(i);
			String id = reservationIds.get(i);
			cancels.add(() -> mockMvc.perform(post("/reservations/" + id + "/cancel").header("Authorization", auth))
					.andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = runTogether(cancels);

		assertThat(statuses).as("statuses %s", statuses).allMatch(s -> s == 200);
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", show)).isEqualTo(users * 2);
		assertThat(count("SELECT COALESCE(SUM(held_count), 0) FROM user_show_counts WHERE show_id = ?", show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isZero();
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ? AND status = 'cancelled'", show)).isEqualTo(users);
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ? AND status = 'pending'", show)).isZero();
	}

}
