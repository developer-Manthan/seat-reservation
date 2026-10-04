package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.manthan.seat_reservation.repository.SeatStore;
import com.manthan.seat_reservation.service.DbFailures;

/**
 * Creates a REAL deadlock (1213) and a REAL lock wait timeout (1205) in MySQL, through the same JPA path the reserve
 * transaction uses, and checks that the retry classifier recognises what Spring and Hibernate actually throw.
 * Not @Transactional: the two transactions have to run on their own connections and really commit or roll back.
 */
@SpringBootTest
class LockFailureMappingTest {

	@Autowired
	SeatStore store;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	private final List<Long> showIds = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		for (long showId : showIds) {
			jdbc.update("DELETE FROM seats WHERE show_id = ?", showId);
			jdbc.update("DELETE FROM shows WHERE id = ?", showId);
		}
	}

	private long newShowWithTwoSeats() {
		jdbc.update("INSERT INTO shows (name, price_paise, total_seats) VALUES (?, 100, 2)", "t-" + UUID.randomUUID());
		long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		showIds.add(id);
		jdbc.update("INSERT INTO seats (show_id, seat_label) VALUES (?, 'S1'), (?, 'S2')", id, id);
		return id;
	}

	/** The cause chain as text, so a failing assertion shows exactly what was thrown. */
	private static String chain(Throwable error) {
		StringBuilder text = new StringBuilder();
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			text.append(cause.getClass().getSimpleName()).append(" <- ");
		}
		return text.toString();
	}

	@Test
	void aRealDeadlockIsClassifiedRetryable() throws Exception {
		long show = newShowWithTwoSeats();
		CountDownLatch bothHoldTheirFirstSeat = new CountDownLatch(2);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		// Opposite lock order: exactly what the sorted seat order prevents in the real code.
		Callable<Void> first = () -> tx.execute(status -> {
			store.claimSeat(show, "S1");
			bothHoldTheirFirstSeat.countDown();
			await(bothHoldTheirFirstSeat);
			store.claimSeat(show, "S2");
			return null;
		});
		Callable<Void> second = () -> tx.execute(status -> {
			store.claimSeat(show, "S2");
			bothHoldTheirFirstSeat.countDown();
			await(bothHoldTheirFirstSeat);
			store.claimSeat(show, "S1");
			return null;
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Void> a = pool.submit(first);
			Future<Void> b = pool.submit(second);
			List<Throwable> failures = new ArrayList<>();
			for (Future<Void> future : List.of(a, b)) {
				try {
					future.get(30, TimeUnit.SECONDS);
				}
				catch (ExecutionException e) {
					failures.add(e.getCause());
				}
			}

			assertThat(failures).as("MySQL picks one deadlock victim and lets the other finish").hasSize(1);
			Throwable victim = failures.get(0);
			assertThat(DbFailures.isRetryableLockFailure(victim)).as(chain(victim)).isTrue();
			assertThat(DbFailures.describe(victim)).as(chain(victim)).isEqualTo("MySQL 1213 (deadlock)");
			System.out.println("DEADLOCK EXCEPTION CHAIN: " + chain(victim));
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aRealLockWaitTimeoutIsClassifiedRetryable() throws Exception {
		long show = newShowWithTwoSeats();
		CountDownLatch holderHasTheSeat = new CountDownLatch(1);
		CountDownLatch releaseHolder = new CountDownLatch(1);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		AtomicReference<Throwable> captured = new AtomicReference<>();

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<Void> holder = pool.submit(() -> tx.execute(status -> {
				store.claimSeat(show, "S1");
				holderHasTheSeat.countDown();
				await(releaseHolder);
				return null;
			}));
			await(holderHasTheSeat);

			tx.execute(status -> {
				jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
				try {
					store.claimSeat(show, "S1");
					throw new AssertionError("expected a lock wait timeout");
				}
				catch (RuntimeException e) {
					captured.set(e);
				}
				finally {
					jdbc.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
				}
				status.setRollbackOnly();
				return null;
			});
			releaseHolder.countDown();
			holder.get(30, TimeUnit.SECONDS);
		}
		finally {
			releaseHolder.countDown();
			pool.shutdownNow();
		}

		Throwable timeout = captured.get();
		assertThat(timeout).isNotNull();
		assertThat(DbFailures.isRetryableLockFailure(timeout)).as(chain(timeout)).isTrue();
		assertThat(DbFailures.describe(timeout)).as(chain(timeout)).isEqualTo("MySQL 1205 (lock wait timeout)");
		System.out.println("LOCK TIMEOUT EXCEPTION CHAIN: " + chain(timeout));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for the other transaction");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
