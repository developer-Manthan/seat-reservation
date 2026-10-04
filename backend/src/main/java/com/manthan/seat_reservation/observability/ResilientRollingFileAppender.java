package com.manthan.seat_reservation.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;

import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.FileNamePattern;

/**
 * A daily log file that can never stop the application from starting. A normal RollingFileAppender that cannot open
 * its file (missing or read-only folder, wrong permissions) reports an error, and Spring Boot then refuses to start
 * ("Logback configuration error detected"). This one checks the folder first. If it cannot be written, it prints a
 * warning, stays switched off, and logging carries on to stdout.
 */
public class ResilientRollingFileAppender<E> extends RollingFileAppender<E> {

	@Override
	public void start() {
		String problem = problemWithTarget();
		if (problem != null) {
			addWarn("Daily log file disabled, logging to stdout only: " + problem);
			return;
		}
		super.start();
	}

	/** Null if today's log file can be created and appended to, otherwise the reason it cannot. */
	private String problemWithTarget() {
		if (!(getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy) || policy.getFileNamePattern() == null) {
			return null;
		}
		try {
			Path file = Path.of(new FileNamePattern(policy.getFileNamePattern(), getContext()).convert(new Date())).toAbsolutePath();
			Files.createDirectories(file.getParent());
			Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND).close();
			return null;
		}
		catch (IOException | RuntimeException e) {
			return e.toString();
		}
	}

}
