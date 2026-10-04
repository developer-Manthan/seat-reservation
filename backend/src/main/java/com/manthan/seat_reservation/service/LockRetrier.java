package com.manthan.seat_reservation.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.manthan.seat_reservation.observability.ReservationMetrics;

/**
 * Runs a transactional call (reserve or cancel) again after a deadlock or lock timeout, with a small random backoff.
 * It must wrap the call from OUTSIDE the transaction, so every attempt is a fresh one. Only lock failures are retried. Domain
 * declines and every other exception propagate untouched. A pool timeout is not retried (it already waited for the
 * connection timeout). Exhausted retries and pool timeouts both end as {@link ServiceBusyException}, which is a 429.
 */
@Component
public class LockRetrier {

	private static final Logger log = LoggerFactory.getLogger(LockRetrier.class);

	/** Sleeps between attempts. A seam so tests do not really wait. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	private final int maxAttempts;
	private final long baseBackoffMs;
	private final long maxBackoffMs;
	private final Sleeper sleeper;
	private final LongUnaryOperator randomUpTo;
	private final ReservationMetrics metrics;

	@Autowired
	public LockRetrier(@Value("${app.reservation.retry.max-attempts:5}") int maxAttempts,
			@Value("${app.reservation.retry.base-backoff-ms:10}") long baseBackoffMs,
			@Value("${app.reservation.retry.max-backoff-ms:200}") long maxBackoffMs, ReservationMetrics metrics) {
		this(maxAttempts, baseBackoffMs, maxBackoffMs, Thread::sleep,
				bound -> ThreadLocalRandom.current().nextLong(bound + 1), metrics);
	}

	public LockRetrier(int maxAttempts, long baseBackoffMs, long maxBackoffMs, Sleeper sleeper,
			LongUnaryOperator randomUpTo, ReservationMetrics metrics) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("app.reservation.retry.max-attempts must be at least 1");
		}
		this.maxAttempts = maxAttempts;
		this.baseBackoffMs = baseBackoffMs;
		this.maxBackoffMs = maxBackoffMs;
		this.sleeper = sleeper;
		this.randomUpTo = randomUpTo;
		this.metrics = metrics;
	}

	public <T> T run(Supplier<T> attempt) {
		for (int number = 1;; number++) {
			MDC.put("attempt", String.valueOf(number));
			try {
				return attempt.get();
			}
			catch (RuntimeException e) {
				if (DbFailures.isPoolTimeout(e)) {
					log.warn("Connection pool timed out on attempt {}, answering 429", number);
					throw new ServiceBusyException("The database is busy, retry shortly");
				}
				if (!DbFailures.isRetryableLockFailure(e)) {
					throw e;
				}
				if (number >= maxAttempts) {
					log.warn("Lock conflict ({}) survived {} attempts, answering 429", DbFailures.describe(e), number);
					throw new ServiceBusyException("The service is busy, retry shortly");
				}
				metrics.retried();
				long wait = backoff(number);
				log.warn("Lock conflict ({}) on attempt {}, retrying in {} ms", DbFailures.describe(e), number, wait);
				pause(wait);
			}
			finally {
				MDC.remove("attempt");
			}
		}
	}

	/** Random wait between 0 and base * 2^(attempt-1), capped. */
	public long backoff(int failedAttempt) {
		long ceiling = Math.min(maxBackoffMs, baseBackoffMs << Math.min(failedAttempt - 1, 20));
		return randomUpTo.applyAsLong(Math.max(ceiling, 0));
	}

	private void pause(long millis) {
		try {
			sleeper.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ServiceBusyException("The request was interrupted, retry shortly");
		}
	}

}
