package com.manthan.seat_reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "shows")
@Getter
@Setter
@NoArgsConstructor
public class Show {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	/** Integer paise, never float or double. */
	@Column(name = "price_paise")
	private long pricePaise;

	@Column(name = "per_user_limit")
	private int perUserLimit = 4;

	@Column(name = "total_seats")
	private int totalSeats;

}
