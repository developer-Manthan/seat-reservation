package com.manthan.seat_reservation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.repository.SeatStore;

/**
 * Insert-if-absent into users, in its own short transaction. It runs before the reserve transaction starts and
 * is never nested inside it, so a request never holds two pool connections.
 */
@Service
public class UserRegistrar {

	private final SeatStore seatStore;

	public UserRegistrar(SeatStore seatStore) {
		this.seatStore = seatStore;
	}

	@Transactional
	public void ensureUser(String userId) {
		seatStore.ensureUser(userId);
	}

}
