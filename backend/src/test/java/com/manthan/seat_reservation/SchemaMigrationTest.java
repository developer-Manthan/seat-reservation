package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SchemaMigrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void allTablesExist() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
		assertThat(tables).contains("users", "shows", "seats", "reservations", "reservation_seats",
				"user_show_counts", "idempotency_keys");
	}

	@Test
	void statusColumnsAreExactMysqlEnums() {
		assertThat(columnType("seats", "status")).isEqualTo("enum('available','held','confirmed')");
		assertThat(columnType("reservations", "status")).isEqualTo("enum('pending','confirmed','cancelled')");
	}

	@Test
	void seatsDefaultToAvailable() {
		String def = jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'seats' AND column_name = 'status'", String.class);
		assertThat(def).isEqualTo("available");
	}

	@Test
	void moneyAndCountersAreIntegers() {
		assertThat(columnType("shows", "price_paise")).isEqualTo("bigint");
		assertThat(columnType("reservations", "amount_paise")).isEqualTo("bigint");
		assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'shows' AND column_name = 'per_user_limit'",
				String.class)).isEqualTo("4");
	}

	@Test
	void foreignKeysAreNamedAndRestrict() {
		List<Map<String, Object>> fks = jdbc.queryForList(
				"SELECT constraint_name, update_rule, delete_rule FROM information_schema.referential_constraints "
						+ "WHERE constraint_schema = DATABASE()");
		Set<Object> names = Set.copyOf(fks.stream().map(r -> r.get("CONSTRAINT_NAME")).toList());
		assertThat(names).containsExactlyInAnyOrder(
				"fk_seats_show",
				"fk_reservation_seats_seat",
				"fk_reservation_seats_reservation",
				"fk_reservations_show",
				"fk_reservations_user",
				"fk_user_show_counts_show",
				"fk_user_show_counts_user",
				"fk_idempotency_keys_reservation",
				"fk_idempotency_keys_user");
		assertThat(fks).allSatisfy(r -> {
			assertThat(r.get("UPDATE_RULE")).isEqualTo("RESTRICT");
			assertThat(r.get("DELETE_RULE")).isEqualTo("RESTRICT");
		});
	}

	@Test
	void compositeKeysAndIndexesExist() {
		assertThat(indexColumns("seats", "PRIMARY")).containsExactly("show_id", "seat_label");
		assertThat(indexColumns("seats", "idx_seats_show_status")).containsExactly("show_id", "status");
		assertThat(indexColumns("reservation_seats", "PRIMARY")).containsExactly("show_id", "seat_label");
		assertThat(indexColumns("reservation_seats", "idx_reservation_seats_reservation"))
				.containsExactly("reservation_id");
		assertThat(indexColumns("user_show_counts", "PRIMARY")).containsExactly("user_id", "show_id");
		assertThat(indexColumns("idempotency_keys", "PRIMARY")).containsExactly("user_id", "idem_key");
	}

	@Test
	void showNamesAreUnique() {
		assertThat(indexColumns("shows", "uk_shows_name")).containsExactly("name");
		assertThat(jdbc.queryForObject("SELECT non_unique FROM information_schema.statistics WHERE table_schema = DATABASE() "
				+ "AND table_name = 'shows' AND index_name = 'uk_shows_name' LIMIT 1", Integer.class)).isZero();
	}

	@Test
	void seatsHaveNoOwnerColumns() {
		List<String> seatCols = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name IN ('seats','reservation_seats')", String.class);
		assertThat(seatCols).doesNotContain("user_id");
	}

	private String columnType(String table, String column) {
		return jdbc.queryForObject("SELECT column_type FROM information_schema.columns WHERE table_schema = DATABASE() "
				+ "AND table_name = ? AND column_name = ?", String.class, table, column);
	}

	private List<String> indexColumns(String table, String index) {
		return jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
				+ "AND table_name = ? AND index_name = ? ORDER BY seq_in_index", String.class, table, index);
	}

}
