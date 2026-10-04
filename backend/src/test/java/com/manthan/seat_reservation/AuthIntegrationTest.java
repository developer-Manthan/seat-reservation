package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.manthan.seat_reservation.auth.Principal;
import com.manthan.seat_reservation.auth.RequireRole;
import com.manthan.seat_reservation.auth.Role;
import com.manthan.seat_reservation.auth.TokenService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The 401/403 matrix, run through the real interceptor against test-only endpoints (POST /shows does not exist
 * yet, its own auth checks live with that endpoint). Rolls back, so the users it creates do not persist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthIntegrationTest.TestEndpoints.class)
@Transactional
class AuthIntegrationTest {

	static final String ADMIN_TOKEN = "dev-only-admin-token";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	TokenService tokenService;

	/** Nested in a test class, so component scanning skips it. Only this test imports it. */
	@Controller
	@RequestMapping("/test-auth")
	@ResponseBody
	static class TestEndpoints {

		@GetMapping("/user")
		Map<String, Object> user(@RequestAttribute("principal") Principal principal) {
			return who(principal);
		}

		@GetMapping("/admin")
		@RequireRole(Role.ADMIN)
		Map<String, Object> admin(@RequestAttribute("principal") Principal principal) {
			return who(principal);
		}

		/** Ignores any user_id in the body: identity comes from the token only. */
		@PostMapping("/echo")
		Map<String, Object> echo(@RequestAttribute("principal") Principal principal, @RequestBody Map<String, Object> body) {
			Map<String, Object> result = who(principal);
			result.put("body_user_id", body.get("user_id"));
			return result;
		}

		private static Map<String, Object> who(Principal principal) {
			Map<String, Object> result = new HashMap<>();
			result.put("role", principal.role().name());
			result.put("user_id", principal.userId());
			return result;
		}

	}

	private String newUserId() {
		return UUID.randomUUID().toString();
	}

	private String userToken(String userId) {
		return tokenService.issue(userId, tokenService.expiryFromNow());
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	// ---- 401 ----

	@Test
	void noTokenIs401WithTheStandardErrorShape() throws Exception {
		mockMvc.perform(get("/test-auth/user"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(jsonPath("$.error").value("unauthorized"))
				.andExpect(jsonPath("$.message").exists())
				.andExpect(jsonPath("$.trace_id").isNotEmpty())
				.andExpect(header().exists("X-Trace-Id"));
	}

	@Test
	void invalidTamperedAndMalformedTokensAre401() throws Exception {
		String good = userToken(newUserId());
		for (String bad : new String[] { "garbage", good + "x", good.substring(0, good.length() - 2), "" }) {
			mockMvc.perform(get("/test-auth/user").header("Authorization", bearer(bad)))
					.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(get("/test-auth/user").header("Authorization", "Basic " + good))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/test-auth/user").header("Authorization", good))
				.andExpect(status().isUnauthorized());
	}

	// ---- 403 ----

	@Test
	void userTokenOnAnAdminEndpointIs403() throws Exception {
		mockMvc.perform(get("/test-auth/admin").header("Authorization", bearer(userToken(newUserId()))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("forbidden"));
	}

	// ---- allowed ----

	@Test
	void adminTokenOnAnAdminEndpointWorksAndCarriesNoUserId() throws Exception {
		mockMvc.perform(get("/test-auth/admin").header("Authorization", bearer(ADMIN_TOKEN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.user_id").value(nullValue()));
	}

	@Test
	void adminRequestsDoNotCreateUsers() throws Exception {
		Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);

		mockMvc.perform(get("/test-auth/admin").header("Authorization", bearer(ADMIN_TOKEN))).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(before);
	}

	@Test
	void userTokenOnAUserEndpointWorksAndCreatesTheUserRow() throws Exception {
		String userId = newUserId();

		mockMvc.perform(get("/test-auth/user").header("Authorization", bearer(userToken(userId))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.user_id").value(userId));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);
	}

	@Test
	void repeatedRequestsFromTheSameUserNeverFailOnTheDuplicateInsert() throws Exception {
		String token = userToken(newUserId());

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get("/test-auth/user").header("Authorization", bearer(token))).andExpect(status().isOk());
		}
	}

	@Test
	void adminTokenIsAcceptedOnEndpointsThatNeedOnlyAnyAuthenticatedCaller() throws Exception {
		mockMvc.perform(get("/test-auth/user").header("Authorization", bearer(ADMIN_TOKEN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void userIdInTheRequestBodyIsIgnored() throws Exception {
		String realUser = newUserId();
		String claimedUser = newUserId();

		mockMvc.perform(post("/test-auth/echo")
						.header("Authorization", bearer(userToken(realUser)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"user_id\":\"" + claimedUser + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user_id").value(realUser))
				.andExpect(jsonPath("$.body_user_id").value(claimedUser));
	}

	// ---- POST /auth/token ----

	@Test
	void tokenEndpointNeedsNoAuthAndTheTokenWorks() throws Exception {
		String userId = newUserId();

		MvcResult result = mockMvc.perform(post("/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"user_id\":\"" + userId + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user_id").value(userId))
				.andExpect(jsonPath("$.expires_at").exists())
				.andReturn();
		JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);
		mockMvc.perform(get("/test-auth/user").header("Authorization", bearer(json.get("token").asString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user_id").value(userId));
	}

	@Test
	void tokenEndpointNormalisesTheUserIdToLowercaseAndIsRepeatable() throws Exception {
		String upper = newUserId().toUpperCase();

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
							.content("{\"user_id\":\"" + upper + "\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.user_id").value(upper.toLowerCase()));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, upper.toLowerCase()))
				.isEqualTo(1);
	}

	@Test
	void tokenEndpointRejectsAMissingOrNonUuidUserIdWith400() throws Exception {
		for (String body : new String[] { "{}", "{\"user_id\":\"alice\"}", "{\"user_id\":\"\"}", "{\"user_id\":123}",
				"{\"user_id\":\"1-1-1-1-1\"}", "not json" }) {
			mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error").value("bad-request"));
		}
		for (String body : new String[] { "{}", "{\"user_id\":\"alice\"}" }) {
			mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("user_id: ")));
		}
	}

	// ---- open and protected paths ----

	@Test
	void healthAndReadinessStayOpen() throws Exception {
		mockMvc.perform(get("/healthz")).andExpect(status().isOk());
		mockMvc.perform(get("/readyz")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void unknownPathsAreProtectedByDefault() throws Exception {
		mockMvc.perform(get("/no-such-endpoint")).andExpect(status().isUnauthorized());
	}

	@Test
	void getOnTheTokenPathServesNothing() throws Exception {
		mockMvc.perform(get("/auth/token")).andExpect(status().isMethodNotAllowed());
	}

}
