package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.manthan.seat_reservation.observability.ResilientRollingFileAppender;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.FileNamePattern;
import ch.qos.logback.core.status.Status;

/** The daily file: its name follows the Asia/Kolkata day, and a folder that cannot be written never stops the app. */
class LogFileConfigTest {

	private static LoggerContext newContext() {
		LoggerContext context = new LoggerContext();
		context.setMDCAdapter(new LogbackMDCAdapter());
		return context;
	}

	/** Sets an appender up the way logback-spring.xml does, pointing at the given folder. */
	private static <A extends RollingFileAppender<ILoggingEvent>> A configure(A file, LoggerContext context, Path directory) {
		file.setContext(context);
		TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
		policy.setContext(context);
		policy.setParent(file);
		policy.setFileNamePattern(directory.toString().replace(File.separatorChar, '/') + "/%d{ddMMyyyy,Asia/Kolkata}.log");
		policy.setMaxHistory(30);
		policy.start();
		file.setRollingPolicy(policy);
		PatternLayoutEncoder encoder = new PatternLayoutEncoder();
		encoder.setContext(context);
		encoder.setPattern("%msg%n");
		encoder.start();
		file.setEncoder(encoder);
		file.start();
		return file;
	}

	/** Spring Boot refuses to start if Logback reported any ERROR while being configured. */
	private static List<Status> errors(LoggerContext context) {
		return context.getStatusManager().getCopyOfStatusList().stream().filter(s -> s.getLevel() == Status.ERROR).toList();
	}

	@Test
	void theFileNameChangesAtMidnightInKolkataNotAtMidnightUtc() {
		FileNamePattern pattern = new FileNamePattern("/logs/%d{ddMMyyyy,Asia/Kolkata}.log", new LoggerContext());

		// 18:29 UTC on 3 Oct is 23:59 in Kolkata. 18:30 UTC is already 00:00 on 4 Oct there.
		assertThat(pattern.convert(Date.from(Instant.parse("2026-10-03T18:29:59Z")))).isEqualTo("/logs/03102026.log");
		assertThat(pattern.convert(Date.from(Instant.parse("2026-10-03T18:30:00Z")))).isEqualTo("/logs/04102026.log");
		assertThat(pattern.convert(Date.from(Instant.parse("2026-10-03T23:59:59Z")))).isEqualTo("/logs/04102026.log");
	}

	@Test
	void aWritableFolderIsCreatedAndGetsTheDatedFile(@TempDir Path temp) throws IOException {
		Path logs = temp.resolve("not-created-yet/logs");
		LoggerContext context = newContext();

		ResilientRollingFileAppender<ILoggingEvent> file = configure(new ResilientRollingFileAppender<>(), context, logs);
		Logger logger = context.getLogger("test");
		logger.addAppender(file);
		logger.info("first line");
		logger.info("second line");
		file.stop();

		assertThat(errors(context)).isEmpty();
		List<Path> files;
		try (var stream = Files.list(logs)) {
			files = stream.toList();
		}
		assertThat(files).hasSize(1);
		assertThat(files.get(0).getFileName().toString()).matches("[0-9]{8}[.]log");
		assertThat(Files.readAllLines(files.get(0))).containsExactly("first line", "second line");
	}

	@Test
	void anUnwritableFolderOnlyGivesAWarningSoTheAppStillStarts(@TempDir Path temp) throws IOException {
		// The "folder" is a regular file, so no log file can ever be created inside it.
		Path notAFolder = Files.writeString(temp.resolve("not-a-folder"), "x");

		LoggerContext plain = newContext();
		configure(new RollingFileAppender<>(), plain, notAFolder);
		assertThat(errors(plain)).as("a plain appender reports an ERROR here, which is what makes Spring Boot refuse to start").isNotEmpty();

		LoggerContext resilient = newContext();
		ResilientRollingFileAppender<ILoggingEvent> file = configure(new ResilientRollingFileAppender<>(), resilient, notAFolder);
		assertThat(errors(resilient)).as("no ERROR, so the app starts and keeps logging to stdout").isEmpty();
		assertThat(file.isStarted()).isFalse();
		assertThat(resilient.getStatusManager().getCopyOfStatusList())
				.anyMatch(s -> s.getLevel() == Status.WARN && s.getMessage().contains("Daily log file disabled"));

		// Logging to a switched-off appender does nothing and throws nothing.
		Logger logger = resilient.getLogger("test");
		logger.addAppender(file);
		logger.info("still fine");
		assertThat(errors(resilient)).isEmpty();
	}

}
