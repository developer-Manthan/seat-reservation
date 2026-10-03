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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorAccessTest {

	@LocalServerPort
	int port;

	@Autowired
	TokenService tokenService;

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

	@Test
	void healthStaysOpen() throws Exception {
		assertThat(status("/healthz", null)).isEqualTo(200);
		assertThat(status("/readyz", null)).isEqualTo(200);
		assertThat(status("/actuator/health", null)).isEqualTo(200);
	}

}
