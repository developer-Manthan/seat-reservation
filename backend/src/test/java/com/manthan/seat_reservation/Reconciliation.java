package com.manthan.seat_reservation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs scripts/reconcile.sql, the same file you can run by hand after a load test, and returns violations per
 * check. Every value must be 0. The statements are global, so the test schema must be clean around the test.
 */
final class Reconciliation {

	private Reconciliation() {
	}

	static Map<String, Long> run(JdbcTemplate jdbc) {
		Map<String, Long> violations = new LinkedHashMap<>();
		for (String statement : statements()) {
			Map<String, Object> row = jdbc.queryForMap(statement);
			violations.put((String) row.get("check_name"), ((Number) row.get("violations")).longValue());
		}
		return violations;
	}

	/** Only the checks that found something, for a readable assertion message. */
	static Map<String, Long> failures(JdbcTemplate jdbc) {
		Map<String, Long> failures = new LinkedHashMap<>();
		run(jdbc).forEach((check, count) -> {
			if (count != 0) {
				failures.put(check, count);
			}
		});
		return failures;
	}

	private static List<String> statements() {
		Path script = Path.of("../scripts/reconcile.sql");
		if (!Files.exists(script)) {
			script = Path.of("scripts/reconcile.sql");
		}
		try {
			StringBuilder sql = new StringBuilder();
			for (String line : Files.readAllLines(script)) {
				if (!line.stripLeading().startsWith("--")) {
					sql.append(line).append('\n');
				}
			}
			List<String> statements = new ArrayList<>();
			for (String part : sql.toString().split(";")) {
				if (!part.isBlank()) {
					statements.add(part.trim());
				}
			}
			return statements;
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot read " + script.toAbsolutePath(), e);
		}
	}

}
