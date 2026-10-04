package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;

/** The trace id: on every response, the same in the error body, and never trusted blindly from the client. */
@SpringBootTest
@AutoConfigureMockMvc
class TraceIdFilterTest {

	private static final String ADMIN = "Bearer dev-only-admin-token";
	private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
	private static final Pattern UUID_FORMAT = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper json;

	private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse();
	}

	@Test
	void everyKindOfResponseCarriesATraceId() throws Exception {
		List<MockHttpServletRequestBuilder> requests = List.of(
				get("/healthz"),                                                                              // 200
				get("/actuator/health"),                                                                      // 200
				get("/actuator/metrics"),                                                                     // 401 from the actuator filter
				get("/actuator/metrics").header("Authorization", ADMIN),                                     // 200
				get("/shows/1"),                                                                              // 401
				get("/shows/999999999").header("Authorization", ADMIN),                                      // 404
				get("/shows/abc").header("Authorization", ADMIN),                                            // 400
				get("/no-such-path").header("Authorization", ADMIN),                                         // 404
				post("/shows").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{}"),        // 400
				post("/shows").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON).content("not json"),  // 400
				post("/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"nope\"}"));             // 400

		for (MockHttpServletRequestBuilder request : requests) {
			MockHttpServletResponse response = call(request);

			assertThat(response.getHeader("X-Trace-Id")).as("status %d", response.getStatus()).isNotNull().matches(VALID);
		}
	}

	@Test
	void errorBodiesCarryTheSameTraceIdAsTheHeader() throws Exception {
		for (MockHttpServletRequestBuilder request : List.of(get("/shows/1"), get("/actuator/metrics"),
				get("/shows/999999999").header("Authorization", ADMIN))) {
			MockHttpServletResponse response = call(request);

			assertThat(json.readTree(response.getContentAsString()).get("trace_id").asString())
					.as("status %d", response.getStatus()).isEqualTo(response.getHeader("X-Trace-Id"));
		}
	}

	@Test
	void aValidIncomingTraceIdIsKept() throws Exception {
		MockHttpServletResponse response = call(get("/shows/1").header("X-Trace-Id", "client-trace-0001"));

		assertThat(response.getHeader("X-Trace-Id")).isEqualTo("client-trace-0001");
		assertThat(json.readTree(response.getContentAsString()).get("trace_id").asString()).isEqualTo("client-trace-0001");
	}

	@Test
	void anIncomingTraceIdThatCouldInjectLogLinesIsReplacedByAUuid() throws Exception {
		for (String bad : new String[] { "abc", "x".repeat(65), "has spaces in it", "under_score_id1", "line1\r\nX-Injected: yes",
				"{\"level\":\"ERROR\"}", "abcdefgh\nfake log line", "" }) {
			MockHttpServletResponse response = call(get("/healthz").header("X-Trace-Id", bad));

			assertThat(response.getHeader("X-Trace-Id")).as("incoming '%s'", bad.replace("\r", "\\r").replace("\n", "\\n")).matches(UUID_FORMAT);
			assertThat(response.getHeader("X-Injected")).isNull();
		}
	}

	@Test
	void theTraceIdOfAW3cTraceparentIsUsedAndAnInvalidOneIsIgnored() throws Exception {
		String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

		assertThat(call(get("/healthz").header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")).getHeader("X-Trace-Id"))
				.isEqualTo(traceId);
		for (String bad : new String[] { "garbage", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01", "00-short-00f067aa0ba902b7-01" }) {
			assertThat(call(get("/healthz").header("traceparent", bad)).getHeader("X-Trace-Id")).as(bad).matches(UUID_FORMAT);
		}
	}

	@Test
	void nothingIsLeftInTheMdcAfterARequest() throws Exception {
		call(get("/shows/999999999").header("Authorization", ADMIN).header("X-Trace-Id", "mdc-check-0001"));

		assertThat(MDC.get("trace_id")).isNull();
		assertThat(MDC.get("user_id")).isNull();
	}

}
