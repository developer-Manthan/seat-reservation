package com.manthan.seat_reservation.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Rows are created by a native INSERT IGNORE, so this entity is read-only. */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyId.class)
@Getter
@NoArgsConstructor
public class IdempotencyKey {

	@Id
	@Column(name = "user_id")
	private String userId;

	@Id
	@Column(name = "idem_key")
	private String idemKey;

	@Column(name = "request_hash")
	private String requestHash;

	@Column(name = "reservation_id")
	private String reservationId;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

}
