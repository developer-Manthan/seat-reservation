package com.manthan.seat_reservation.observability;

import java.util.List;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reservation counters. Names are dotted, so Prometheus exposes reservations_confirmed_total,
 * reservations_declined_total{reason} and reservations_partial_total. Only call these after a transaction ended.
 */
@Component
public class ReservationMetrics {

	public static final String SEAT_TAKEN = "seat-taken";
	public static final String PER_USER_LIMIT = "per-user-limit";
	public static final String IDEMPOTENT_REPLAY = "idempotent-replay";
	public static final String IDEMPOTENCY_CONFLICT = "idempotency-conflict";

	private final MeterRegistry registry;
	private final Counter confirmed;
	private final Counter partial;
	private final Counter cancelled;
	private final Counter retries;
	private final Counter throttled;

	public ReservationMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.confirmed = Counter.builder("reservations.confirmed").description("Reservations confirmed").register(registry);
		this.partial = Counter.builder("reservations.partial")
				.description("best_effort requests that booked fewer seats than requested").register(registry);
		this.cancelled = Counter.builder("reservations.cancelled").description("Reservations cancelled").register(registry);
		this.retries = Counter.builder("reservations.retries").description("Reserve attempts retried after a lock conflict")
				.register(registry);
		this.throttled = Counter.builder("requests.throttled").description("Requests answered with 429").register(registry);
		// Register every reason up front so the series exist at 0.
		List.of(SEAT_TAKEN, PER_USER_LIMIT, IDEMPOTENT_REPLAY, IDEMPOTENCY_CONFLICT).forEach(this::declinedCounter);
	}

	public void confirmed() {
		confirmed.increment();
	}

	public void partial() {
		partial.increment();
	}

	public void cancelled() {
		cancelled.increment();
	}

	public void retried() {
		retries.increment();
	}

	public void throttled() {
		throttled.increment();
	}

	public void declined(String reason) {
		declinedCounter(reason).increment();
	}

	private Counter declinedCounter(String reason) {
		return Counter.builder("reservations.declined").description("Reservation requests that did not book new seats")
				.tag("reason", reason).register(registry);
	}

}
