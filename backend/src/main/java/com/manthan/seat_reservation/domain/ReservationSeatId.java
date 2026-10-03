package com.manthan.seat_reservation.domain;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class ReservationSeatId implements Serializable {

	private Long showId;
	private String seatLabel;

}
