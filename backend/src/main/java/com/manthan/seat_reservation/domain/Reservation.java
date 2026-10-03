package com.manthan.seat_reservation.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Created and updated through explicit queries. The id is an app-generated UUID. */
@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor
public class Reservation {

	@Id
	private String id;

	@Column(name = "show_id")
	private Long showId;

	@Column(name = "user_id")
	private String userId;

	@Column(name = "amount_paise")
	private long amountPaise;

	@Enumerated(EnumType.STRING)
	private ReservationStatus status;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

}
