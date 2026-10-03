package com.manthan.seat_reservation.api;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.manthan.seat_reservation.repository.HealthRepository;

import jakarta.annotation.PreDestroy;

@RestController
public class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);

	private final HealthRepository healthRepository;
	private final long timeoutMs;
	// The pool timeout is long (requests queue), so the probe runs off-thread with its own short cap.
	private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public HealthController(HealthRepository healthRepository,
			@Value("${app.readiness.timeout-ms:2000}") long timeoutMs) {
		this.healthRepository = healthRepository;
		this.timeoutMs = timeoutMs;
	}

	/** Liveness: the process is up. Never touches the database. */
	@GetMapping("/healthz")
	public Map<String, String> healthz() {
		return Map.of("status", "UP");
	}

	/** Readiness: a real SELECT 1. Fails closed (503) on any error or timeout. */
	@GetMapping("/readyz")
	public ResponseEntity<Map<String, String>> readyz() {
		Future<?> probe = probeExecutor.submit(healthRepository::ping);
		try {
			probe.get(timeoutMs, TimeUnit.MILLISECONDS);
			return ResponseEntity.ok(Map.of("status", "ready"));
		}
		catch (Exception e) {
			probe.cancel(true);
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("Readiness check failed: {}", e.toString());
			return ResponseEntity.status(503).body(Map.of("status", "not-ready"));
		}
	}

	@PreDestroy
	void shutdown() {
		probeExecutor.shutdownNow();
	}

}
