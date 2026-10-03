package com.manthan.seat_reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A row exists only while the seat is booked. Inserted with a native INSERT and deleted with a JPQL DELETE
 * (never save(), which would SELECT first). The owner is reservations.user_id, reached through reservation_id.
 */
@Entity
@Table(name = "reservation_seats")
@IdClass(ReservationSeatId.class)
@Getter
@NoArgsConstructor
public class ReservationSeat {

	@Id
	@Column(name = "show_id")
	private Long showId;

	@Id
	@Column(name = "seat_label")
	private String seatLabel;

	@Column(name = "reservation_id")
	private String reservationId;

}
