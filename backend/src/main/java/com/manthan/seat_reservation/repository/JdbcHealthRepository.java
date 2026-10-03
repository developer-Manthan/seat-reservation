package com.manthan.seat_reservation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHealthRepository implements HealthRepository {

	private final JdbcTemplate jdbcTemplate;

	JdbcHealthRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void ping() {
		Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
		if (result == null || result != 1) {
			throw new IllegalStateException("SELECT 1 returned " + result);
		}
	}

}
