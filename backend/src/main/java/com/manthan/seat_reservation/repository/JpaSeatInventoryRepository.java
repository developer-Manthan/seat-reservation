package com.manthan.seat_reservation.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.manthan.seat_reservation.domain.Seat;
import com.manthan.seat_reservation.domain.SeatStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Repository
class JpaSeatInventoryRepository implements SeatInventoryRepository {

	/** Matches hibernate.jdbc.batch_size, so each chunk goes out as one JDBC batch. */
	private static final int CHUNK = 500;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * persist() never reads first (save() on an assigned composite id would SELECT before every INSERT).
	 * After each chunk the batch is flushed and the persistence context cleared, so memory stays flat.
	 */
	@Override
	public void insertSeats(long showId, List<String> seatLabels) {
		int inChunk = 0;
		for (String label : seatLabels) {
			entityManager.persist(new Seat(showId, label, SeatStatus.available));
			if (++inChunk == CHUNK) {
				flushChunk();
				inChunk = 0;
			}
		}
		if (inChunk > 0) {
			flushChunk();
		}
	}

	private void flushChunk() {
		entityManager.flush();
		entityManager.clear();
	}

}
