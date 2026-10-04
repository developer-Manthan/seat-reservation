package com.manthan.seat_reservation.service;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Classifies database failures for the retry loop and the 429 mapping. Both checks walk the cause chain,
 * because Spring, Hibernate and the driver each wrap the original error.
 */
public final class DbFailures {

	public static final int MYSQL_DEADLOCK = 1213;
	public static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

	private static final int MAX_DEPTH = 12;

	private DbFailures() {
	}

	/**
	 * A deadlock or lock timeout that is safe to retry in a fresh transaction. Matches Spring's
	 * PessimisticLockingFailureException (its subclasses are DeadlockLoserDataAccessException and
	 * CannotAcquireLockException) or a MySQL 1213 / 1205 error anywhere in the cause chain.
	 */
	public static boolean isRetryableLockFailure(Throwable error) {
		int depth = 0;
		for (Throwable cause = error; cause != null && depth < MAX_DEPTH; cause = cause.getCause(), depth++) {
			if (cause instanceof PessimisticLockingFailureException) {
				return true;
			}
			if (cause instanceof SQLException sql && isLockErrorCode(sql.getErrorCode())) {
				return true;
			}
		}
		return false;
	}

	/** The connection pool ran out of connections: Hikari reports "request timed out" after the connection timeout. */
	public static boolean isPoolTimeout(Throwable error) {
		int depth = 0;
		for (Throwable cause = error; cause != null && depth < MAX_DEPTH; cause = cause.getCause(), depth++) {
			if (cause instanceof SQLTransientConnectionException && cause.getMessage() != null
					&& cause.getMessage().contains("request timed out")) {
				return true;
			}
		}
		return false;
	}

	/** Short description for logs, for example "MySQL 1213 (deadlock)". */
	public static String describe(Throwable error) {
		int depth = 0;
		for (Throwable cause = error; cause != null && depth < MAX_DEPTH; cause = cause.getCause(), depth++) {
			if (cause instanceof SQLException sql && isLockErrorCode(sql.getErrorCode())) {
				return "MySQL " + sql.getErrorCode() + (sql.getErrorCode() == MYSQL_DEADLOCK ? " (deadlock)" : " (lock wait timeout)");
			}
		}
		String name = error.getClass().getSimpleName();
		return name.isEmpty() ? error.getClass().getName() : name;
	}

	private static boolean isLockErrorCode(int code) {
		return code == MYSQL_DEADLOCK || code == MYSQL_LOCK_WAIT_TIMEOUT;
	}

}
