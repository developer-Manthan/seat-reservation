package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import com.manthan.seat_reservation.service.DbFailures;
import com.manthan.seat_reservation.service.SeatTakenException;

import java.util.List;

class DbFailuresTest {

	private static SQLException mysql(int code) {
		return new SQLException("mysql error", "40001", code);
	}

	@Test
	void springLockExceptionsAreRetryable() {
		assertThat(DbFailures.isRetryableLockFailure(new DeadlockLoserDataAccessException("x", mysql(1213)))).isTrue();
		assertThat(DbFailures.isRetryableLockFailure(new CannotAcquireLockException("x", mysql(1205)))).isTrue();
		assertThat(DbFailures.isRetryableLockFailure(new PessimisticLockingFailureException("x"))).isTrue();
	}

	@Test
	void mysqlCodes1213And1205AnywhereInTheCauseChainAreRetryable() {
		assertThat(DbFailures.isRetryableLockFailure(new RuntimeException("outer", new IllegalStateException(mysql(1213))))).isTrue();
		assertThat(DbFailures.isRetryableLockFailure(new RuntimeException("outer", mysql(1205)))).isTrue();
		assertThat(DbFailures.isRetryableLockFailure(mysql(1213))).isTrue();
	}

	@Test
	void otherFailuresAreNotRetryable() {
		assertThat(DbFailures.isRetryableLockFailure(new DataIntegrityViolationException("dup", mysql(1062)))).isFalse();
		assertThat(DbFailures.isRetryableLockFailure(new RuntimeException("boom"))).isFalse();
		assertThat(DbFailures.isRetryableLockFailure(new SeatTakenException(List.of("A1")))).isFalse();
		assertThat(DbFailures.isRetryableLockFailure(mysql(1062))).isFalse();
	}

	@Test
	void poolTimeoutIsDetectedThroughWrappers() {
		SQLTransientConnectionException hikari = new SQLTransientConnectionException(
				"HikariPool-1 - Connection is not available, request timed out after 25000ms (total=25, active=25, idle=0, waiting=40)");

		assertThat(DbFailures.isPoolTimeout(hikari)).isTrue();
		assertThat(DbFailures.isPoolTimeout(new CannotCreateTransactionException("Could not open JPA EntityManager",
				new RuntimeException("jdbc", hikari)))).isTrue();
	}

	@Test
	void otherConnectionProblemsAreNotPoolTimeouts() {
		assertThat(DbFailures.isPoolTimeout(new SQLTransientConnectionException("something else"))).isFalse();
		assertThat(DbFailures.isPoolTimeout(new SQLTransientConnectionException())).isFalse();
		assertThat(DbFailures.isPoolTimeout(new RuntimeException("boom"))).isFalse();
		assertThat(DbFailures.isPoolTimeout(mysql(1213))).isFalse();
	}

	@Test
	void describeNamesTheMysqlCode() {
		assertThat(DbFailures.describe(new RuntimeException(mysql(1213)))).isEqualTo("MySQL 1213 (deadlock)");
		assertThat(DbFailures.describe(new RuntimeException(mysql(1205)))).isEqualTo("MySQL 1205 (lock wait timeout)");
		assertThat(DbFailures.describe(new PessimisticLockingFailureException("x"))).isEqualTo("PessimisticLockingFailureException");
	}

	@Test
	void aSelfReferencingCauseChainDoesNotHang() {
		RuntimeException loop = new RuntimeException("loop") {
			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		assertThat(DbFailures.isRetryableLockFailure(loop)).isFalse();
		assertThat(DbFailures.isPoolTimeout(loop)).isFalse();
		assertThat(DbFailures.describe(loop)).isNotBlank();
	}

}
