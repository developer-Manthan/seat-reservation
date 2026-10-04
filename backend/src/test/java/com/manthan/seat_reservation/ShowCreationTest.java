package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.auth.TokenService;

/** POST /shows through the real auth interceptor. Rolls back, so no shows are left in the test schema. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShowCreationTest {

	private static final String ADMIN = "Bearer dev-only-admin-token";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TokenService tokenService;

	private ResultActions createShow(String authorization, String json) throws Exception {
		var request = post("/shows").contentType(MediaType.APPLICATION_JSON).content(json);
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return mockMvc.perform(request);
	}

	private static String show(String name, Object price, Object seats) {
		return "{\"name\":" + name + ",\"price_paise\":" + price + ",\"seats\":" + seats + "}";
	}

	private static String seatList(int count) {
		return "[" + java.util.stream.IntStream.rangeClosed(1, count).mapToObj(i -> "\"S" + i + "\"")
				.collect(java.util.stream.Collectors.joining(",")) + "]";
	}

	private static final String VALID = show("\"Coldplay\"", "250000", "[\"A1\",\"A2\",\"A3\",\"B1\",\"B2\",\"B3\"]");

	// ---- auth ----

	@Test
	void noTokenIs401() throws Exception {
		createShow(null, VALID).andExpect(status().isUnauthorized());
	}

	@Test
	void userTokenIs403() throws Exception {
		String userToken = tokenService.issue(UUID.randomUUID().toString(), tokenService.expiryFromNow());

		createShow("Bearer " + userToken, VALID).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shows WHERE name = 'Coldplay'", Integer.class)).isZero();
	}

	@Test
	void adminTokenIs201() throws Exception {
		createShow(ADMIN, VALID).andExpect(status().isCreated());
	}

	// ---- creation ----

	@Test
	void createsTheShowAndEverySeatAsAvailable() throws Exception {
		String body = createShow(ADMIN, VALID)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Coldplay"))
				.andExpect(jsonPath("$.price_paise").value(250000))
				.andExpect(jsonPath("$.per_user_limit").value(4))
				.andExpect(jsonPath("$.total_seats").value(6))
				.andExpect(jsonPath("$.seats.length()").value(6))
				.andExpect(jsonPath("$.seats[0].seat_label").value("A1"))
				.andExpect(jsonPath("$.seats[5].seat_label").value("B3"))
				.andExpect(jsonPath("$.seats[*].status", everyItem(is("available"))))
				.andReturn().getResponse().getContentAsString();
		long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

		List<String> labels = jdbc.queryForList("SELECT seat_label FROM seats WHERE show_id = ? ORDER BY seat_label", String.class, id);
		assertThat(labels).containsExactly("A1", "A2", "A3", "B1", "B2", "B3");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", Integer.class, id))
				.isEqualTo(6);
		Map<String, Object> row = jdbc.queryForMap("SELECT name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?", id);
		assertThat(row).containsEntry("name", "Coldplay").containsEntry("price_paise", 250000L)
				.containsEntry("per_user_limit", 4).containsEntry("total_seats", 6);
	}

	@Test
	void locationHeaderPointsAtTheNewShow() throws Exception {
		createShow(ADMIN, VALID).andExpect(header().string("Location", startsWith("/shows/")));
	}

	@Test
	void invariantAvailablePlusHeldPlusConfirmedEqualsTotalSeats() throws Exception {
		createShow(ADMIN, VALID).andExpect(status().isCreated());

		Map<String, Object> counts = jdbc.queryForMap("SELECT s.total_seats AS total, "
				+ "SUM(se.status = 'available') AS available, SUM(se.status = 'held') AS held, SUM(se.status = 'confirmed') AS confirmed "
				+ "FROM shows s JOIN seats se ON se.show_id = s.id WHERE s.name = 'Coldplay' GROUP BY s.id");
		long sum = ((Number) counts.get("available")).longValue() + ((Number) counts.get("held")).longValue()
				+ ((Number) counts.get("confirmed")).longValue();
		assertThat(sum).isEqualTo(((Number) counts.get("total")).longValue());
	}

	@Test
	void perUserLimitCanBeSet() throws Exception {
		createShow(ADMIN, "{\"name\":\"Limited\",\"price_paise\":100,\"per_user_limit\":2,\"seats\":[\"A1\",\"A2\"]}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.per_user_limit").value(2));
	}

	@Test
	void freeShowsWithZeroPriceAreAllowed() throws Exception {
		createShow(ADMIN, show("\"Free\"", "0", "[\"A1\"]")).andExpect(status().isCreated());
	}

	@Test
	void lowercaseLabelsAreStoredAndReturnedInUppercase() throws Exception {
		createShow(ADMIN, show("\"Cases\"", "1", "[\"a1\",\"b2\",\"vip-3\",\"Mixed.4\"]"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats[0].seat_label").value("A1"))
				.andExpect(jsonPath("$.seats[1].seat_label").value("B2"))
				.andExpect(jsonPath("$.seats[2].seat_label").value("VIP-3"))
				.andExpect(jsonPath("$.seats[3].seat_label").value("MIXED.4"));

		assertThat(jdbc.queryForList("SELECT seat_label FROM seats se JOIN shows s ON s.id = se.show_id WHERE s.name = 'Cases' ORDER BY seat_label",
				String.class)).containsExactly("A1", "B2", "MIXED.4", "VIP-3");
	}

	@Test
	void sameLabelInDifferentCaseIsADuplicate() throws Exception {
		for (String seats : new String[] { "[\"a1\",\"A1\"]", "[\"A1\",\"a1\"]", "[\"vip-1\",\"VIP-1\"]" }) {
			createShow(ADMIN, show("\"Dup\"", "1", seats))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value(startsWith("seats: duplicate seat")));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shows WHERE name = 'Dup'", Integer.class)).isZero();
	}

	@Test
	void responseKeepsTheOrderOfTheRequest() throws Exception {
		createShow(ADMIN, show("\"Order\"", "1", "[\"C3\",\"A1\",\"B2\"]"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats[0].seat_label").value("C3"))
				.andExpect(jsonPath("$.seats[1].seat_label").value("A1"))
				.andExpect(jsonPath("$.seats[2].seat_label").value("B2"));
	}

	@Test
	void exactlyTheMaximumNumberOfSeatsIsAccepted() throws Exception {
		createShow(ADMIN, show("\"Arena\"", "1", seatList(5000)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.total_seats").value(5000));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats se JOIN shows s ON s.id = se.show_id WHERE s.name = 'Arena'", Integer.class))
				.isEqualTo(5000);
	}

	// ---- unique name ----

	@Test
	void secondShowWithTheSameNameIs409AndNothingIsSaved() throws Exception {
		createShow(ADMIN, show("\"friday-night\"", "1", "[\"A1\"]")).andExpect(status().isCreated());

		createShow(ADMIN, show("\"friday-night\"", "2", "[\"B1\",\"B2\"]"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("show-name-taken"))
				.andExpect(jsonPath("$.message").value(startsWith("A show named 'friday-night' already exists")));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shows WHERE name = 'friday-night'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats se JOIN shows s ON s.id = se.show_id WHERE s.name = 'friday-night'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void showNamesAreUniqueIgnoringCaseAndSurroundingSpaces() throws Exception {
		createShow(ADMIN, show("\"Friday Night\"", "1", "[\"A1\"]")).andExpect(status().isCreated());

		for (String name : new String[] { "\"friday night\"", "\"FRIDAY NIGHT\"", "\"  Friday Night  \"" }) {
			createShow(ADMIN, show(name, "1", "[\"A1\"]")).andExpect(status().isConflict());
		}
	}

	@Test
	void differentNamesAreFine() throws Exception {
		createShow(ADMIN, show("\"Friday Night\"", "1", "[\"A1\"]")).andExpect(status().isCreated());
		createShow(ADMIN, show("\"Friday Night 2\"", "1", "[\"A1\"]")).andExpect(status().isCreated());
	}

	// ---- validation ----

	@Test
	void invalidRequestsAre400WithTheStandardErrorShape() throws Exception {
		String[][] cases = {
				{ "{}", "" },
				{ show("\"\"", "1", "[\"A1\"]"), "name: " },
				{ show("\"   \"", "1", "[\"A1\"]"), "name: " },
				{ show("null", "1", "[\"A1\"]"), "name: " },
				{ show("\"x\"", "-1", "[\"A1\"]"), "price_paise: " },
				{ show("\"x\"", "null", "[\"A1\"]"), "price_paise: " },
				{ show("\"x\"", "12.5", "[\"A1\"]"), "" },
				{ show("\"x\"", "\"100\"", "[\"A1\"]"), "" },
				{ show("\"x\"", "1", "[]"), "seats: " },
				{ show("\"x\"", "1", "null"), "seats: " },
				{ show("\"x\"", "1", "[\"\"]"), "seats[0]: " },
				{ show("\"x\"", "1", "[\"A 1\"]"), "seats[0]: " },
				{ show("\"x\"", "1", "[\"A1\",\"B/2\"]"), "seats[1]: " },
				{ show("\"x\"", "1", "[null]"), "seats[0]: " },
				{ show("\"x\"", "1", "[\"" + "A".repeat(33) + "\"]"), "seats[0]: " },
				{ show("\"x\"", "1", "[1]"), "" },
				{ show("\"x\"", "1", "[\"A1\",\"A2\",\"A1\"]"), "seats: duplicate" },
				{ show("\"x\"", "1", seatList(5001)), "seats: 5001 seats requested" },
				{ "{\"name\":\"x\",\"price_paise\":1,\"per_user_limit\":0,\"seats\":[\"A1\"]}", "per_user_limit: " },
				{ "{\"name\":\"x\",\"price_paise\":1,\"rows\":[\"A\"],\"seats_per_row\":2}", "seats: " },
				{ "not json", "" },
		};
		for (String[] testCase : cases) {
			ResultActions result = createShow(ADMIN, testCase[0]);
			assertThat(result.andReturn().getResponse().getStatus())
					.as("body: %s", testCase[0].length() > 120 ? testCase[0].substring(0, 120) : testCase[0]).isEqualTo(400);
			result.andExpect(jsonPath("$.error").value("bad-request"))
					.andExpect(jsonPath("$.message").value(startsWith(testCase[1])));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shows WHERE name IN ('x', '')", Integer.class)).isZero();
	}

}
