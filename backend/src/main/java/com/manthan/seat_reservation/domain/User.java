package com.manthan.seat_reservation.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Rows are created by a native INSERT IGNORE, so this entity is read-only. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

	@Id
	private String id;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

}
