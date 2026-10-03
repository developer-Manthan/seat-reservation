package com.manthan.seat_reservation;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.manthan.seat_reservation.repository.HealthRepository;

@SpringBootTest(properties = "app.readiness.timeout-ms=300")
@AutoConfigureMockMvc
class HealthControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	HealthRepository healthRepository;

	@Test
	void healthzIsUpAndDoesNotTouchTheDatabase() throws Exception {
		doThrow(new IllegalStateException("db down")).when(healthRepository).ping();

		mockMvc.perform(get("/healthz"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readyzIsReadyWhenDatabaseAnswers() throws Exception {
		doNothing().when(healthRepository).ping();

		mockMvc.perform(get("/readyz"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ready"));
	}

	@Test
	void readyzFailsClosedWhenDatabaseThrows() throws Exception {
		doThrow(new IllegalStateException("db down")).when(healthRepository).ping();

		mockMvc.perform(get("/readyz"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("not-ready"));
	}

	@Test
	void readyzFailsClosedWhenDatabaseIsTooSlow() throws Exception {
		doAnswer(invocation -> {
			Thread.sleep(5000);
			return null;
		}).when(healthRepository).ping();

		mockMvc.perform(get("/readyz"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("not-ready"));
	}

}
