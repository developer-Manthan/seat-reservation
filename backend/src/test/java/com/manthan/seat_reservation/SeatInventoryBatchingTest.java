package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.repository.SeatInventoryRepository;

import jakarta.persistence.EntityManager;

/**
 * Seats are created with persist() in chunks. Hibernate statistics show the inserts go out as JDBC batches
 * (a handful of prepared statements, not one per seat) and that persist() issues no SELECT first.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class SeatInventoryBatchingTest {

	@Autowired
	SeatInventoryRepository seatInventory;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EntityManager em;

	@Test
	void insertsSeatsInChunksWithoutReadingFirst() {
		jdbc.update("INSERT INTO shows (name, price_paise, total_seats) VALUES ('Batch', 1, 1200)");
		Long showId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		List<String> labels = new ArrayList<>();
		for (int i = 1; i <= 1200; i++) {
			labels.add("S" + i);
		}
		Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		seatInventory.insertSeats(showId, labels);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id = ?", Integer.class, showId)).isEqualTo(1200);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", Integer.class, showId))
				.isEqualTo(1200);
		assertThat(statistics.getEntityInsertCount()).isEqualTo(1200);
		// 3 chunks of up to 500. Without batching this would be 1200 prepared statements.
		assertThat(statistics.getPrepareStatementCount()).isLessThan(20);
		assertThat(statistics.getEntityLoadCount()).isZero();
	}

}
