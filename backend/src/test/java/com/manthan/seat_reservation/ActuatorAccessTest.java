package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.manthan.seat_reservation.auth.TokenService;

/**
 * Real HTTP server, because MockMvc does not serve actuator endpoints. Metrics are not public: they need the
 * admin token, while health and readiness stay open. Creates a users row for the user token (random UUID, test schema).
 */
// Spring Boot switches metrics export off inside tests. This turns the Prometheus export back on, as in the real app.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.prometheus.metrics.export.enabled=true")
class ActuatorAccessTest {

	@LocalServerPort
	int port;

	@Autowired
	TokenService tokenService;

	@Autowired
	org.springframework.jdbc.core.JdbcTemplate jdbc;

	private final HttpClient client = HttpClient.newHttpClient();

	private int status(String path, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	@Test
	void prometheusAndMetricsAreNotPublic() throws Exception {
		String userToken = tokenService.issue(UUID.randomUUID().toString(), tokenService.expiryFromNow());

		for (String path : new String[] { "/actuator/prometheus", "/actuator/metrics" }) {
			assertThat(status(path, null)).as(path + " without a token").isEqualTo(401);
			assertThat(status(path, userToken)).as(path + " with a user token").isEqualTo(403);
		}
	}

	@Test
	void metricsEndpointServesTheAdminToken() throws Exception {
		assertThat(status("/actuator/metrics", "dev-only-admin-token")).isEqualTo(200);
	}

	private String scrape() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
				.header("Authorization", "Bearer dev-only-admin-token").GET().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	@Test
	void prometheusServesOurMetricsToTheAdminToken() throws Exception {
		assertThat(scrape()).contains("reservations_confirmed_total", "reservations_declined_total{reason=\"seat-taken\"}",
				"reservations_declined_total{reason=\"per-user-limit\"}", "reservations_declined_total{reason=\"idempotent-replay\"}",
				"reservations_partial_total", "reservations_cancelled_total", "reservations_retries_total", "requests_throttled_total");
	}

	@Test
	void seatsAvailableIsLiveOnEveryScrapeWithNoWaiting() throws Exception {
		jdbc.update("INSERT INTO shows (name, price_paise, total_seats) VALUES (?, 100, 3)", "t-" + UUID.randomUUID());
		long show = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		try {
			jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'A1'), (?, 'A2'), (?, 'A3')", show, show, show);

			// The test never asks for a refresh. Each scrape reads the database by itself.
			assertThat(scrape()).contains("seats_available{show_id=\"" + show + "\"} 3.0");

			jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ? AND seat_label = 'A1'", show);
			assertThat(scrape()).contains("seats_available{show_id=\"" + show + "\"} 2.0");

			jdbc.update("UPDATE seats SET status = 'confirmed' WHERE show_id = ?", show);
			assertThat(scrape()).contains("seats_available{show_id=\"" + show + "\"} 0.0");
		}
		finally {
			jdbc.update("DELETE FROM seats WHERE show_id = ?", show);
			jdbc.update("DELETE FROM shows WHERE id = ?", show);
		}
	}

	@Test
	void theMetricsJsonEndpointIsLiveToo() throws Exception {
		jdbc.update("INSERT INTO shows (name, price_paise, total_seats) VALUES (?, 100, 2)", "t-" + UUID.randomUUID());
		long show = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		try {
			jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'A1'), (?, 'A2')", show, show);
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/metrics/seats.available?tag=show_id:" + show))
					.header("Authorization", "Bearer dev-only-admin-token").GET().build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"value\":2.0");
		}
		finally {
			jdbc.update("DELETE FROM seats WHERE show_id = ?", show);
			jdbc.update("DELETE FROM shows WHERE id = ?", show);
		}
	}

	@Test
	void healthStaysOpen() throws Exception {
		assertThat(status("/healthz", null)).isEqualTo(200);
		assertThat(status("/readyz", null)).isEqualTo(200);
		assertThat(status("/actuator/health", null)).isEqualTo(200);
	}

}
