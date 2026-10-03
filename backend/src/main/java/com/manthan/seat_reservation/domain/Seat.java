package com.manthan.seat_reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Inventory only: no owner fields. Seat state changes only through guarded UPDATE queries, never by
 * loading an entity, checking its status and saving it.
 */
@Entity
@Table(name = "seats")
@IdClass(SeatId.class)
@Getter
@NoArgsConstructor
public class Seat {

	@Id
	@Column(name = "show_id")
	private Long showId;

	@Id
	@Column(name = "seat_label")
	private String seatLabel;

	@Enumerated(EnumType.STRING)
	private SeatStatus status;

}
