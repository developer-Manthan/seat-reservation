package com.manthan.seat_reservation.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.manthan.seat_reservation.domain.Show;

public interface ShowRepository extends Repository<Show, Long> {

	Show save(Show show);

	Optional<Show> findById(Long id);

}
