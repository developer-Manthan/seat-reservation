package com.manthan.seat_reservation.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.manthan.seat_reservation.auth.ForbiddenException;
import com.manthan.seat_reservation.auth.Principal;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.service.InvalidRequestException;
import com.manthan.seat_reservation.service.ReservationService;
import com.manthan.seat_reservation.service.ReservationService.ReservationResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
public class ReservationController {

	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	/**
	 * The user id is never part of the body: identity comes from the token only. The key may also come as the
	 * Idempotency-Key header. At most 500 labels are accepted before duplicates are removed.
	 */
	public record ReserveRequest(
			@NotEmpty(message = "is required") @Size(max = 500, message = "must have at most 500 entries") List<@NotNull(message = "must not be null")
					@Pattern(regexp = "^[A-Za-z0-9._-]{1,32}$", message = "must be 1 to 32 letters, digits, '.', '_' or '-'") String> seats,
			@JsonProperty("idempotency_key") String idempotencyKey,
			String mode) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ReserveResponse(@JsonProperty("reservation_id") String reservationId, @JsonProperty("show_id") long showId,
			ReservationStatus status, @JsonProperty("amount_paise") long amountPaise, List<String> seats,
			@JsonProperty("unavailable_seats") List<String> unavailableSeats) {

		static ReserveResponse of(ReservationResult result) {
			return new ReserveResponse(result.reservationId(), result.showId(), result.status(), result.amountPaise(),
					result.seats(), result.unavailableSeats());
		}

	}

	/** Always 201: a new booking and a replay of an earlier one look the same to the client. */
	@PostMapping("/shows/{id}/reserve")
	public ResponseEntity<ReserveResponse> reserve(@PathVariable("id") Long showId,
			@RequestAttribute(Principal.ATTRIBUTE) Principal principal,
			@RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
			@Valid @RequestBody ReserveRequest request) {
		if (principal.userId() == null) {
			throw new ForbiddenException("Admin tokens cannot reserve seats");
		}
		String key = resolveKey(headerKey, request.idempotencyKey());
		ReservationResult result = reservationService.reserve(principal.userId(), showId, request.seats(), key, request.mode());
		return ResponseEntity.status(201).body(ReserveResponse.of(result));
	}

	private static String resolveKey(String headerKey, String bodyKey) {
		if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
			throw new InvalidRequestException("idempotency_key: the Idempotency-Key header and the body value differ");
		}
		String key = bodyKey != null ? bodyKey : headerKey;
		if (key == null) {
			throw new InvalidRequestException("idempotency_key: is required (body field or Idempotency-Key header)");
		}
		return key;
	}

}
