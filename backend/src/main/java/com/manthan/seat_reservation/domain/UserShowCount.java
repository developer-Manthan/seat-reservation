package com.manthan.seat_reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_show_counts")
@IdClass(UserShowCountId.class)
@Getter
@NoArgsConstructor
public class UserShowCount {

	@Id
	@Column(name = "user_id")
	private String userId;

	@Id
	@Column(name = "show_id")
	private Long showId;

	@Column(name = "held_count")
	private int heldCount;

}
