package com.manthan.seat_reservation.observability;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.manthan.seat_reservation.domain.SeatStatus;
import com.manthan.seat_reservation.repository.SeatReadRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The seats_available metric: one value per show, as seats_available{show_id="5"}.
 * It is live: every time the metrics are requested (/actuator/prometheus or /actuator/metrics), it is read from the
 * database with one query, just before the response is built. Nothing is counted in memory, so it is right after a
 * restart and with several instances, and it agrees with GET /shows/{id}.
 * This filter runs after ActuatorAuthFilter, so only a caller with the admin token can trigger the query.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SeatsAvailableGauge extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(SeatsAvailableGauge.class);

	private final SeatReadRepository seatReads;
	private final MultiGauge gauge;

	public SeatsAvailableGauge(SeatReadRepository seatReads, MeterRegistry registry) {
		this.seatReads = seatReads;
		this.gauge = MultiGauge.builder("seats.available").description("Seats still available, per show").register(registry);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !(path.equals("/actuator/prometheus") || path.startsWith("/actuator/metrics"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		refresh();
		chain.doFilter(request, response);
	}

	/** Reads the available seats of every show from the database and updates the metric. */
	public synchronized void refresh() {
		try {
			List<MultiGauge.Row<?>> rows = seatReads.countAvailableSeatsPerShow(SeatStatus.available).stream()
					.<MultiGauge.Row<?>>map(show -> MultiGauge.Row.of(Tags.of("show_id", String.valueOf(show.getShowId())), show.getAvailable()))
					.toList();
			gauge.register(rows, true);
		}
		catch (RuntimeException e) {
			// A metric must never break the metrics endpoint. Keep the last values and answer with those.
			log.warn("Could not refresh the seats_available metric: {}", e.toString());
		}
	}

}
