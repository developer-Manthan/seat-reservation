package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import com.manthan.seat_reservation.observability.ReservationMetrics;
import com.manthan.seat_reservation.service.PerUserLimitException;
import com.manthan.seat_reservation.service.LockRetrier;
import com.manthan.seat_reservation.service.SeatTakenException;
import com.manthan.seat_reservation.service.ServiceBusyException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LockRetrierTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final ReservationMetrics metrics = new ReservationMetrics(registry);
	private final List<Long> sleeps = new ArrayList<>();

	private LockRetrier retrier(int maxAttempts) {
		return new LockRetrier(maxAttempts, 10, 200, sleeps::add, bound -> bound, metrics);
	}

	private double retries() {
		return registry.get("reservations.retries").counter().count();
	}

	private static RuntimeException deadlock() {
		return new DeadlockLoserDataAccessException("deadlock", new SQLException("Deadlock found", "40001", 1213));
	}

	/** Fails with the given error for the first {@code failures} calls, then returns "ok". */
	private static java.util.function.Supplier<String> failing(AtomicInteger calls, int failures, RuntimeException error) {
		return () -> {
			if (calls.incrementAndGet() <= failures) {
				throw error;
			}
			return "ok";
		};
	}

	@Test
	void succeedsOnTheFirstTryWithoutSleeping() {
		AtomicInteger calls = new AtomicInteger();

		assertThat(retrier(5).run(failing(calls, 0, deadlock()))).isEqualTo("ok");

		assertThat(calls).hasValue(1);
		assertThat(sleeps).isEmpty();
		assertThat(retries()).isZero();
	}

	@Test
	void retriesDeadlocksAndSucceedsOnTheThirdAttempt() {
		AtomicInteger calls = new AtomicInteger();

		assertThat(retrier(5).run(failing(calls, 2, deadlock()))).isEqualTo("ok");

		assertThat(calls).hasValue(3);
		assertThat(sleeps).hasSize(2);
		assertThat(retries()).isEqualTo(2);
	}

	@Test
	void givesUpAfterTheMaximumAndAnswersBusyWithoutSleepingAfterTheLastAttempt() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> retrier(5).run(failing(calls, 99, deadlock()))).isInstanceOf(ServiceBusyException.class);

		assertThat(calls).hasValue(5);
		assertThat(sleeps).hasSize(4);
		assertThat(retries()).isEqualTo(4);
	}

	@Test
	void aSingleAttemptConfigurationNeverRetries() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> retrier(1).run(failing(calls, 99, deadlock()))).isInstanceOf(ServiceBusyException.class);

		assertThat(calls).hasValue(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void everyKindOfLockFailureIsRetried() {
		RuntimeException[] errors = {
				new CannotAcquireLockException("lock wait timeout", new SQLException("Lock wait timeout exceeded", "HY000", 1205)),
				new PessimisticLockingFailureException("pessimistic"),
				new RuntimeException("wrapped", new SQLException("Deadlock found", "40001", 1213)),
		};
		for (RuntimeException error : errors) {
			AtomicInteger calls = new AtomicInteger();

			assertThat(retrier(3).run(failing(calls, 1, error))).isEqualTo("ok");
			assertThat(calls).as(error.getMessage()).hasValue(2);
		}
	}

	@Test
	void domainDeclinesAndOtherErrorsAreNeverRetried() {
		RuntimeException[] errors = {
				new SeatTakenException(List.of("A1")),
				new PerUserLimitException(2, 1),
				new DataIntegrityViolationException("duplicate"),
				new IllegalStateException("bug"),
		};
		for (RuntimeException error : errors) {
			AtomicInteger calls = new AtomicInteger();

			assertThatThrownBy(() -> retrier(5).run(failing(calls, 99, error))).isSameAs(error);
			assertThat(calls).as(error.getClass().getSimpleName()).hasValue(1);
		}
		assertThat(sleeps).isEmpty();
		assertThat(retries()).isZero();
	}

	@Test
	void poolTimeoutIsNotRetriedAndAnswersBusyAtOnce() {
		AtomicInteger calls = new AtomicInteger();
		RuntimeException poolTimeout = new CannotCreateTransactionException("Could not open JPA EntityManager",
				new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 25000ms"));

		assertThatThrownBy(() -> retrier(5).run(failing(calls, 99, poolTimeout))).isInstanceOf(ServiceBusyException.class);

		assertThat(calls).hasValue(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void backoffDoublesFromTheBaseAndStopsAtTheCap() {
		LockRetrier retrier = retrier(10);

		// The fake random always picks the upper bound, so these are the ceilings: 10, 20, 40, 80, 160, then capped at 200.
		assertThat(List.of(retrier.backoff(1), retrier.backoff(2), retrier.backoff(3), retrier.backoff(4), retrier.backoff(5),
				retrier.backoff(6), retrier.backoff(7), retrier.backoff(30)))
				.containsExactly(10L, 20L, 40L, 80L, 160L, 200L, 200L, 200L);
	}

	@Test
	void backoffIsRandomWithinTheCeiling() {
		LockRetrier real = new LockRetrier(5, 10, 200, millis -> { }, bound -> java.util.concurrent.ThreadLocalRandom.current().nextLong(bound + 1), metrics);

		for (int i = 0; i < 500; i++) {
			assertThat(real.backoff(3)).isBetween(0L, 40L);
		}
		assertThat(new LockRetrier(5, 10, 200, millis -> { }, bound -> 0, metrics).backoff(4)).isZero();
	}

	@Test
	void theAttemptNumberIsInTheMdcDuringEachAttemptAndClearedAfterwards() {
		List<String> seen = new ArrayList<>();
		AtomicInteger calls = new AtomicInteger();

		retrier(5).run(() -> {
			seen.add(MDC.get("attempt"));
			if (calls.incrementAndGet() < 3) {
				throw deadlock();
			}
			return "ok";
		});

		assertThat(seen).containsExactly("1", "2", "3");
		assertThat(MDC.get("attempt")).isNull();
	}

	@Test
	void theMdcIsClearedEvenWhenTheCallFails() {
		assertThatThrownBy(() -> retrier(5).run(() -> {
			throw new IllegalStateException("bug");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(MDC.get("attempt")).isNull();
	}

	@Test
	void anInterruptedSleepAnswersBusyAndKeepsTheInterruptFlag() {
		LockRetrier interrupted = new LockRetrier(5, 10, 200, millis -> {
			throw new InterruptedException();
		}, bound -> bound, metrics);
		AtomicInteger calls = new AtomicInteger();

		try {
			assertThatThrownBy(() -> interrupted.run(failing(calls, 99, deadlock()))).isInstanceOf(ServiceBusyException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
		assertThat(calls).hasValue(1);
	}

	@Test
	void aMaximumOfZeroAttemptsIsRejected() {
		assertThatThrownBy(() -> new LockRetrier(0, 10, 200, sleeps::add, bound -> bound, metrics))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
