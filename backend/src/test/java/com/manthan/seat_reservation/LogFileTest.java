package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.manthan.seat_reservation.auth.TokenService;
import com.manthan.seat_reservation.service.ReserveTransaction;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads what was really written to the daily log file (target/test-logs, set by LOG_DIR in the test config).
 * Each request sends its own X-Trace-Id, so its lines can be found among everything else. The file is written by an
 * async appender, so lookups poll for a moment.
 */
@SpringBootTest(properties = { "app.reservation.retry.base-backoff-ms=1", "app.reservation.retry.max-backoff-ms=2" })
@AutoConfigureMockMvc
class LogFileTest {

	private static final Path LOG_DIR = Path.of("target/test-logs");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	@Autowired
	ObjectMapper json;

	@MockitoSpyBean
	ReserveTransaction reserveTransaction;

	private long showId;
	private final List<String> userIds = new ArrayList<>();

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
		userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
	}

	private void newShow() {
		jdbc.update("INSERT INTO shows (name, price_paise, per_user_limit, total_seats) VALUES (?, 100, 4, 2)", "t-" + UUID.randomUUID());
		showId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'A1'), (?, 'A2')", showId, showId);
	}

	private String newUserId() {
		String id = UUID.randomUUID().toString();
		userIds.add(id);
		return id;
	}

	private String token(String userId) {
		return tokenService.issue(userId, tokenService.expiryFromNow());
	}

	private static String newTraceId() {
		return "log-test-" + UUID.randomUUID();
	}

	private MockHttpServletRequestBuilder reserve(String token, String seat, String traceId) {
		return post("/shows/" + showId + "/reserve").header("Authorization", "Bearer " + token).header("X-Trace-Id", traceId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"" + seat + "\"],\"idempotency_key\":\"k-" + seat + "\"}");
	}

	private List<String> rawLines() throws IOException {
		try (Stream<Path> files = Files.list(LOG_DIR)) {
			List<String> lines = new ArrayList<>();
			for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".log")).toList()) {
				lines.addAll(Files.readAllLines(file));
			}
			return lines;
		}
	}

	/** The lines of one request, found by trace id. */
	private List<JsonNode> linesOf(String traceId, int atLeast) throws Exception {
		List<JsonNode> found = List.of();
		for (int i = 0; i < 100; i++) {
			found = rawLines().stream().filter(l -> l.contains(traceId)).map(json::readTree).toList();
			if (found.size() >= atLeast) {
				return found;
			}
			Thread.sleep(100);
		}
		return found;
	}

	private static List<String> messages(List<JsonNode> lines) {
		return lines.stream().map(l -> l.get("message").asString()).toList();
	}

	@Test
	void theTraceIdAndUserIdOfARequestAreOnItsLinesInTheDaysLogFile() throws Exception {
		newShow();
		String userId = newUserId();
		String traceId = newTraceId();

		var response = mockMvc.perform(reserve(token(userId), "A1", traceId)).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getHeader("X-Trace-Id")).isEqualTo(traceId);

		List<JsonNode> lines = linesOf(traceId, 2);
		assertThat(messages(lines)).anyMatch(m -> m.startsWith("Reservation confirmed"))
				.anyMatch(m -> m.startsWith("POST /shows/" + showId + "/reserve -> 201"));
		assertThat(lines).allSatisfy(line -> {
			assertThat(line.get("trace_id").asString()).isEqualTo(traceId);
			assertThat(line.get("user_id").asString()).isEqualTo(userId);
		});
		// Each line says where its log call was written.
		JsonNode confirmed = lines.stream().filter(l -> l.get("message").asString().startsWith("Reservation confirmed")).findFirst().orElseThrow();
		assertThat(confirmed.get("file").asString()).isEqualTo("ReservationService.java");
		assertThat(confirmed.get("function").asString()).isEqualTo("reserve");
		JsonNode summary = lines.stream().filter(l -> l.get("message").asString().startsWith("POST ")).findFirst().orElseThrow();
		assertThat(summary.get("file").asString()).isEqualTo("TraceIdFilter.java");
	}

	@Test
	void everyLineIsAJsonObjectWithTimestampLevelFileFunctionAndMessage() throws Exception {
		String traceId = newTraceId();
		mockMvc.perform(get("/shows/999999999").header("Authorization", "Bearer dev-only-admin-token").header("X-Trace-Id", traceId));
		linesOf(traceId, 1);

		List<String> raw = rawLines();

		assertThat(raw).isNotEmpty();
		for (String line : raw) {
			JsonNode node = json.readTree(line);
			assertThat(OffsetDateTime.parse(node.get("timestamp").asString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)).as(line).isNotNull();
			assertThat(node.get("level").asString()).as(line).isIn("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
			assertThat(node.get("file").asString()).as(line).isNotBlank();
			assertThat(node.get("function").asString()).as(line).isNotBlank();
			assertThat(node.has("message")).as(line).isTrue();
			assertThat(node.has("logger")).as("logger is replaced by file and function").isFalse();
		}
	}

	@Test
	void aRequestWithoutAValidTokenHasATraceIdButNoUserId() throws Exception {
		String traceId = newTraceId();
		mockMvc.perform(get("/shows/1").header("Authorization", "Bearer forged.token").header("X-Trace-Id", traceId));

		List<JsonNode> lines = linesOf(traceId, 1);

		assertThat(messages(lines)).containsExactly("GET /shows/1 -> 401 (" + durationOf(lines.get(0)) + " ms)");
		assertThat(lines.get(0).has("user_id")).as("an unverified token never puts a user id in the logs").isFalse();
	}

	private static String durationOf(JsonNode line) {
		return line.get("message").asString().replaceAll(".*\\((\\d+) ms\\)$", "$1");
	}

	@Test
	void retriesKeepTheTraceIdAndCountTheAttempts() throws Exception {
		newShow();
		RuntimeException deadlock = new CannotAcquireLockException("deadlock", new SQLException("Deadlock found", "40001", 1213));
		doThrow(deadlock).doThrow(deadlock).doCallRealMethod().when(reserveTransaction).reserveOnce(any());
		String traceId = newTraceId();

		assertThat(mockMvc.perform(reserve(token(newUserId()), "A1", traceId)).andReturn().getResponse().getStatus()).isEqualTo(201);

		List<JsonNode> retries = linesOf(traceId, 4).stream().filter(l -> l.get("message").asString().startsWith("Lock conflict")).toList();
		assertThat(retries).hasSize(2);
		assertThat(retries).extracting(l -> l.get("attempt").asString()).containsExactly("1", "2");
		assertThat(retries).allSatisfy(l -> assertThat(l.get("level").asString()).isEqualTo("WARN"));
	}

	@Test
	void tokensAndSecretsNeverAppearInTheLogFile() throws Exception {
		newShow();
		String userToken = token(newUserId());
		String traceId = newTraceId();
		mockMvc.perform(reserve(userToken, "A2", traceId));
		mockMvc.perform(post("/shows").header("Authorization", "Bearer dev-only-admin-token").contentType(MediaType.APPLICATION_JSON).content("{}"));
		linesOf(traceId, 2);
		Thread.sleep(300);

		String everything = String.join("\n", rawLines());

		assertThat(everything).doesNotContain(userToken).doesNotContain("dev-only-admin-token").doesNotContain("dev-only-secret")
				.doesNotContain("Bearer ");
	}

}
