package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real concurrency against a real MySQL: many threads released at the same instant through the real endpoints (auth,
 * interceptor, retry loop, transactions). Data is committed, so each test cleans up after itself, and every test ends
 * with the same reconciliation script you can run by hand (scripts/reconcile.sql). The pool is large enough for the
 * thread counts, and requests queue for a connection instead of failing. In every scenario there must be no 5xx and
 * no 429. Sizes are constants so they are easy to turn up.
 */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=40", "spring.datasource.hikari.minimum-idle=5" })
@AutoConfigureMockMvc
class RaceTest {

	static final int HOT_SEAT_USERS = 1000;
	static final int SAME_KEY_COPIES = 100;
	static final int OVERLAP_USERS = 200;
	static final int OVERLAP_SEATS = 60;
	static final int OVERLAP_SEATS_PER_REQUEST = 3;
	static final int ONE_USER_PARALLEL = 100;
	static final int CANCEL_RACE_ROUNDS = 200;

	record Result(int status, String body) {
		boolean created() {
			return status == 201;
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	MeterRegistry meters;

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
		for (int from = 0; from < userIds.size(); from += 500) {
			List<String> chunk = userIds.subList(from, Math.min(from + 500, userIds.size()));
			jdbc.update("DELETE FROM users WHERE id IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")", chunk.toArray());
		}
	}

	// ---- fixtures ----

	private long newShow(int perUserLimit, long pricePaise, List<String> seats) {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?)",
				"t-" + UUID.randomUUID(), pricePaise, perUserLimit, seats.size());
		long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		showIds.add(id);
		jdbc.batchUpdate("INSERT INTO seats (show_id, seat_label) VALUES (?, ?)", seats.stream().map(s -> new Object[] { id, s }).toList());
		return id;
	}

	private static List<String> seatNames(int count) {
		return IntStream.rangeClosed(1, count).mapToObj(i -> "S" + i).toList();
	}

	private String newUserId() {
		String id = UUID.randomUUID().toString();
		userIds.add(id);
		return id;
	}

	private String auth(String userId) {
		return "Bearer " + tokenService.issue(userId, tokenService.expiryFromNow());
	}

	private Result reserve(String auth, long show, List<String> seats, String key, String mode) {
		String seatsJson = seats.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
		String body = "{\"seats\":" + seatsJson + ",\"idempotency_key\":\"" + key + "\"" + (mode == null ? "" : ",\"mode\":\"" + mode + "\"") + "}";
		return perform(post("/shows/" + show + "/reserve").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private Result cancel(String auth, String reservationId) {
		return perform(post("/reservations/" + reservationId + "/cancel").header("Authorization", auth));
	}

	private Result perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
		try {
			var response = mockMvc.perform(request).andReturn().getResponse();
			return new Result(response.getStatus(), response.getContentAsString());
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private JsonNode parse(String body) {
		return json.readTree(body);
	}

	private String errorOf(Result result) {
		return parse(result.body()).get("error").asString();
	}

	private List<String> seatsOf(Result result) {
		JsonNode seats = parse(result.body()).get("seats");
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < seats.size(); i++) {
			labels.add(seats.get(i).asString());
		}
		return labels;
	}

	/** Releases all tasks at the same instant and collects their results. */
	private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(180, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---- assertions and reporting ----

	private double meter(String name, String... tags) {
		var search = meters.find(name);
		for (int i = 0; i < tags.length; i += 2) {
			search = search.tag(tags[i], tags[i + 1]);
		}
		var counter = search.counter();
		return counter == null ? 0 : counter.count();
	}

	private Map<Integer, Long> distribution(List<Result> results) {
		return results.stream().collect(Collectors.groupingBy(Result::status, TreeMap::new, Collectors.counting()));
	}

	/** Under a race, a server error or a 429 is a failure. Declines must be clean 409s. */
	private void assertNoServerErrorsOrThrottling(String scenario, List<Result> results) {
		Map<Integer, Long> statuses = distribution(results);
		assertThat(statuses.keySet()).as("%s: status distribution %s", scenario, statuses).allMatch(s -> s < 500 && s != 429);
	}

	private void assertReconciled() {
		assertThat(Reconciliation.failures(jdbc)).as("reconciliation (scripts/reconcile.sql)").isEmpty();
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private void report(String scenario, List<Result> results, double retriesBefore, long startedNanos) {
		System.out.printf("RACE %-34s requests=%-5d statuses=%s retries=%.0f time=%dms%n", scenario, results.size(), distribution(results),
				meter("reservations.retries") - retriesBefore, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
	}

	// ---- 1. one hot seat ----

	private void hotSeat(String mode) throws Exception {
		long show = newShow(4, 100, List.of("HOT"));
		double confirmedBefore = meter("reservations.confirmed");
		double seatTakenBefore = meter("reservations.declined", "reason", "seat-taken");
		double retriesBefore = meter("reservations.retries");
		List<Callable<Result>> tasks = new ArrayList<>();
		for (int i = 0; i < HOT_SEAT_USERS; i++) {
			String auth = auth(newUserId());
			tasks.add(() -> reserve(auth, show, List.of("HOT"), "k", mode));
		}

		long started = System.nanoTime();
		List<Result> results = runTogether(tasks);
		report("hot seat " + mode, results, retriesBefore, started);

		assertNoServerErrorsOrThrottling("hot seat " + mode, results);
		assertThat(results.stream().filter(Result::created).count()).as("exactly one winner").isEqualTo(1);
		assertThat(results.stream().filter(r -> r.status() == 409).map(this::errorOf)).allMatch("seat-taken"::equals).hasSize(HOT_SEAT_USERS - 1);
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", show)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ?", show)).as("declines store no reservation").isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE reservation_id IN (SELECT id FROM reservations WHERE show_id = ?)", show)).isEqualTo(1);
		assertThat(meter("reservations.confirmed")).isEqualTo(confirmedBefore + 1);
		assertThat(meter("reservations.declined", "reason", "seat-taken")).isEqualTo(seatTakenBefore + HOT_SEAT_USERS - 1);
		assertReconciled();
	}

	@Test
	void oneHotSeatAndManyUsersGivesExactlyOneWinnerInAllOrNothing() throws Exception {
		hotSeat("all_or_nothing");
	}

	@Test
	void oneHotSeatAndManyUsersGivesExactlyOneWinnerInBestEffort() throws Exception {
		hotSeat("best_effort");
	}

	// ---- 2. the same request many times at once ----

	@Test
	void theSameKeyAndRequestSentManyTimesAtOnceCreatesExactlyOneReservation() throws Exception {
		long show = newShow(4, 100, List.of("A1", "A2"));
		String auth = auth(newUserId());
		double confirmedBefore = meter("reservations.confirmed");
		double replaysBefore = meter("reservations.declined", "reason", "idempotent-replay");
		double retriesBefore = meter("reservations.retries");
		List<Callable<Result>> tasks = new ArrayList<>();
		for (int i = 0; i < SAME_KEY_COPIES; i++) {
			tasks.add(() -> reserve(auth, show, List.of("A1", "A2"), "same-key", null));
		}

		long started = System.nanoTime();
		List<Result> results = runTogether(tasks);
		report("same key x" + SAME_KEY_COPIES, results, retriesBefore, started);

		assertThat(distribution(results)).as("every copy is a 201 (one booking, the rest replays)").containsOnlyKeys(201);
		Set<String> reservationIds = results.stream().map(r -> parse(r.body()).get("reservation_id").asString()).collect(Collectors.toSet());
		assertThat(reservationIds).hasSize(1);
		assertThat(count("SELECT COUNT(*) FROM reservations WHERE show_id = ?", show)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isEqualTo(2);
		assertThat(meter("reservations.confirmed")).isEqualTo(confirmedBefore + 1);
		assertThat(meter("reservations.declined", "reason", "idempotent-replay")).isEqualTo(replaysBefore + SAME_KEY_COPIES - 1);
		assertReconciled();
	}

	// ---- 3. many users, overlapping seat sets ----

	private void overlappingSeats(String mode) throws Exception {
		List<String> seats = seatNames(OVERLAP_SEATS);
		long show = newShow(4, 100, seats);
		Random random = new Random(42);
		List<String> users = new ArrayList<>();
		List<List<String>> requests = new ArrayList<>();
		List<Callable<Result>> tasks = new ArrayList<>();
		double retriesBefore = meter("reservations.retries");
		for (int i = 0; i < OVERLAP_USERS; i++) {
			String userId = newUserId();
			List<String> wanted = new ArrayList<>(seats);
			Collections.shuffle(wanted, random);
			List<String> pick = List.copyOf(wanted.subList(0, OVERLAP_SEATS_PER_REQUEST));
			users.add(userId);
			requests.add(pick);
			String auth = auth(userId);
			tasks.add(() -> reserve(auth, show, pick, "k", mode));
		}

		long started = System.nanoTime();
		List<Result> results = runTogether(tasks);
		report("overlapping seats " + mode, results, retriesBefore, started);

		assertNoServerErrorsOrThrottling("overlapping " + mode, results);
		Set<String> bookedByResponses = new HashSet<>();
		int bookedSeatCount = 0;
		for (int i = 0; i < results.size(); i++) {
			Result result = results.get(i);
			if (result.created()) {
				List<String> booked = seatsOf(result);
				assertThat(requests.get(i)).containsAll(booked);
				if (mode.equals("all_or_nothing")) {
					assertThat(booked).as("all_or_nothing books everything or nothing").hasSameSizeAs(requests.get(i));
				}
				assertThat(Collections.disjoint(booked, bookedByResponses)).as("a seat is never in two bookings: %s vs %s", booked, bookedByResponses).isTrue();
				bookedByResponses.addAll(booked);
				bookedSeatCount += booked.size();
				if (booked.size() < requests.get(i).size()) {
					JsonNode unavailable = parse(result.body()).get("unavailable_seats");
					assertThat(unavailable.size()).isEqualTo(requests.get(i).size() - booked.size());
				}
			}
			else {
				assertThat(result.status()).isEqualTo(409);
				assertThat(errorOf(result)).isEqualTo("seat-taken");
				assertThat(count("SELECT COUNT(*) FROM reservations WHERE user_id = ?", users.get(i))).as("a decline stores nothing").isZero();
			}
		}
		Set<String> confirmed = new HashSet<>(jdbc.queryForList("SELECT seat_label FROM seats WHERE show_id = ? AND status = 'confirmed'", String.class, show));
		assertThat(confirmed).as("the seats the responses reported are exactly the confirmed seats").isEqualTo(bookedByResponses);
		assertThat(count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ?", show)).isEqualTo(bookedSeatCount);
		assertThat(bookedSeatCount).as("with this much overlap many requests must lose, and many seats must still be sold").isBetween(1, OVERLAP_SEATS);
		assertReconciled();
	}

	@Test
	void manyUsersOnOverlappingSeatSetsNeverDoubleSellInAllOrNothing() throws Exception {
		overlappingSeats("all_or_nothing");
	}

	@Test
	void manyUsersOnOverlappingSeatSetsNeverDoubleSellInBestEffort() throws Exception {
		overlappingSeats("best_effort");
	}

	// ---- 4. the per-user limit under concurrency ----

	@Test
	void oneUserFiringManyRequestsAtOnceNeverGoesOverTheLimit() throws Exception {
		int limit = 5;
		long show = newShow(limit, 100, seatNames(ONE_USER_PARALLEL));
		String auth = auth(newUserId());
		double retriesBefore = meter("reservations.retries");
		List<Callable<Result>> tasks = new ArrayList<>();
		for (int i = 1; i <= ONE_USER_PARALLEL; i++) {
			String seat = "S" + i;
			tasks.add(() -> reserve(auth, show, List.of(seat), "key-" + seat, null));
		}

		long started = System.nanoTime();
		List<Result> results = runTogether(tasks);
		report("one user, limit " + limit, results, retriesBefore, started);

		assertNoServerErrorsOrThrottling("per-user limit", results);
		assertThat(results.stream().filter(Result::created).count()).isEqualTo(limit);
		assertThat(results.stream().filter(r -> r.status() == 409).map(this::errorOf)).allMatch("per-user-limit"::equals).hasSize(ONE_USER_PARALLEL - limit);
		assertThat(count("SELECT held_count FROM user_show_counts WHERE show_id = ?", show)).isEqualTo(limit);
		assertThat(count("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", show)).isEqualTo(limit);
		assertReconciled();
	}

	@Test
	void theLimitHoldsForMultiSeatRequestsToo() throws Exception {
		int limit = 5;
		int requests = 50;
		long show = newShow(limit, 100, seatNames(requests * 2));
		String auth = auth(newUserId());
		double retriesBefore = meter("reservations.retries");
		List<Callable<Result>> tasks = new ArrayList<>();
		for (int i = 0; i < requests; i++) {
			List<String> pair = List.of("S" + (i * 2 + 1), "S" + (i * 2 + 2));
			String key = "pair-" + i;
			tasks.add(() -> reserve(auth, show, pair, key, null));
		}

		long started = System.nanoTime();
		List<Result> results = runTogether(tasks);
		report("one user, 2 seats each, limit " + limit, results, retriesBefore, started);

		assertNoServerErrorsOrThrottling("multi-seat limit", results);
		assertThat(results.stream().filter(Result::created).count()).as("two pairs fit in 5, a third would make 6").isEqualTo(2);
		assertThat(count("SELECT held_count FROM user_show_counts WHERE show_id = ?", show)).isEqualTo(4);
		assertReconciled();
	}

	// ---- 5. the first bookings of one user, all at once ----

	@Test
	void aBurstOfFirstBookingsFromOneUserSucceedsEvenWhenTheCounterRowRaces() throws Exception {
		long show = newShow(ONE_USER_PARALLEL, 100, seatNames(ONE_USER_PARALLEL));
		String auth = auth(newUserId());
		double retriesBefore = meter("reservations.retries");
		List<Callable<Result>> tasks = new ArrayList<>();
		for (int i = 1; i <= ONE_USER_PARALLEL; i++) {
			String seat = "S" + i;
			tasks.add(() -> reserve(auth, show, List.of(seat), "key-" + seat, null));
		}

		long started = System.nanoTime();
		List<Result> results = runTogether(tasks);
		report("first-booking burst", results, retriesBefore, started);

		assertThat(distribution(results)).as("every request books its own seat").containsOnlyKeys(201);
		assertThat(count("SELECT held_count FROM user_show_counts WHERE show_id = ?", show)).isEqualTo(ONE_USER_PARALLEL);
		assertReconciled();
	}

	// ---- 6. cancel racing reserve on the same seat ----

	@Test
	void aCancelRacingNewReservationsLeavesTheSeatWithExactlyOneOwnerOrNone() throws Exception {
		List<String> seats = seatNames(CANCEL_RACE_ROUNDS);
		long show = newShow(4, 100, seats);
		String holderId = newUserId();
		String holder = auth(holderId);
		double retriesBefore = meter("reservations.retries");
		List<Result> all = new ArrayList<>();
		int seatWentToNewOwner = 0;
		int seatEndedFree = 0;
		long started = System.nanoTime();
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			for (int round = 0; round < CANCEL_RACE_ROUNDS; round++) {
				String seat = seats.get(round);
				Result booked = reserve(holder, show, List.of(seat), "hold-" + round, null);
				assertThat(booked.status()).isEqualTo(201);
				String reservationId = parse(booked.body()).get("reservation_id").asString();
				String bidderOne = auth(newUserId());
				String bidderTwo = auth(newUserId());

				CountDownLatch start = new CountDownLatch(1);
				Future<Result> cancel = pool.submit(() -> {
					start.await();
					return cancel(holder, reservationId);
				});
				Future<Result> first = pool.submit(() -> {
					start.await();
					return reserve(bidderOne, show, List.of(seat), "bid-1-" + seat, null);
				});
				Future<Result> second = pool.submit(() -> {
					start.await();
					return reserve(bidderTwo, show, List.of(seat), "bid-2-" + seat, null);
				});
				start.countDown();
				Result cancelled = cancel.get(60, TimeUnit.SECONDS);
				Result bidOne = first.get(60, TimeUnit.SECONDS);
				Result bidTwo = second.get(60, TimeUnit.SECONDS);
				all.addAll(List.of(cancelled, bidOne, bidTwo));

				assertThat(cancelled.status()).as("round %d: the holder always cancels its own confirmed reservation", round).isEqualTo(200);
				long winners = List.of(bidOne, bidTwo).stream().filter(Result::created).count();
				assertThat(winners).as("round %d: at most one new owner", round).isLessThanOrEqualTo(1);
				List.of(bidOne, bidTwo).stream().filter(r -> !r.created()).forEach(r -> {
					assertThat(r.status()).isEqualTo(409);
					assertThat(errorOf(r)).isEqualTo("seat-taken");
				});
				int owners = count("SELECT COUNT(*) FROM reservation_seats WHERE show_id = ? AND seat_label = ?", show, seat);
				String seatStatus = jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, show, seat);
				if (winners == 1) {
					assertThat(owners).as("round %d", round).isEqualTo(1);
					assertThat(seatStatus).as("round %d", round).isEqualTo("confirmed");
					assertThat(jdbc.queryForObject("SELECT r.user_id FROM reservation_seats rs JOIN reservations r ON r.id = rs.reservation_id "
							+ "WHERE rs.show_id = ? AND rs.seat_label = ?", String.class, show, seat)).as("round %d: owner is the winner", round)
							.isNotEqualTo(holderId);
					seatWentToNewOwner++;
				}
				else {
					assertThat(owners).as("round %d", round).isZero();
					assertThat(seatStatus).as("round %d", round).isEqualTo("available");
					seatEndedFree++;
				}
			}
		}
		finally {
			pool.shutdownNow();
		}
		report("cancel vs reserve x" + CANCEL_RACE_ROUNDS, all, retriesBefore, started);
		System.out.printf("RACE cancel vs reserve outcomes: seat went to a new owner in %d rounds, ended free in %d rounds%n", seatWentToNewOwner, seatEndedFree);

		assertNoServerErrorsOrThrottling("cancel vs reserve", all);
		assertReconciled();
	}

}
