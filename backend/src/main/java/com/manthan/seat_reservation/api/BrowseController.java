package com.manthan.seat_reservation.api;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.manthan.seat_reservation.auth.ForbiddenException;
import com.manthan.seat_reservation.auth.Principal;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.service.BrowseService;

/** Read-only lists for the UI (index.html). */
@RestController
public class BrowseController {

	private final BrowseService browseService;

	public BrowseController(BrowseService browseService) {
		this.browseService = browseService;
	}

	public record UserItem(@JsonProperty("user_id") String userId, @JsonProperty("created_at") Instant createdAt) {
	}

	public record ShowItem(Long id, String name, @JsonProperty("price_paise") long pricePaise,
			@JsonProperty("per_user_limit") int perUserLimit, @JsonProperty("total_seats") int totalSeats, long available) {
	}

	public record ReservationItem(@JsonProperty("reservation_id") String reservationId, @JsonProperty("show_id") long showId,
			ReservationStatus status, @JsonProperty("amount_paise") long amountPaise, List<String> seats,
			@JsonProperty("created_at") Instant createdAt) {
	}

	/**
	 * Open, with no token: the UI shows this list so someone can pick who they are before they have a token.
	 * This is a demo app, where anyone may get a token for any user id anyway (POST /auth/token).
	 */
	@GetMapping("/users")
	public List<UserItem> users() {
		return browseService.users().stream().map(u -> new UserItem(u.getId(), u.getCreatedAt())).toList();
	}

	/** Any authenticated caller. Newest show first, each with its number of available seats. */
	@GetMapping("/shows")
	public List<ShowItem> shows() {
		return browseService.shows().stream()
				.map(s -> new ShowItem(s.getId(), s.getName(), s.getPricePaise(), s.getPerUserLimit(), s.getTotalSeats(), s.getAvailable()))
				.toList();
	}

	/** The caller's own reservations, newest first, optionally for one show. */
	@GetMapping("/reservations")
	public List<ReservationItem> reservations(@RequestAttribute(Principal.ATTRIBUTE) Principal principal,
			@RequestParam(name = "show_id", required = false) Long showId) {
		if (principal.userId() == null) {
			throw new ForbiddenException("Admin tokens have no reservations");
		}
		return browseService.reservationsOf(principal.userId(), showId).stream()
				.map(r -> new ReservationItem(r.reservationId(), r.showId(), r.status(), r.amountPaise(), r.seats(), r.createdAt()))
				.toList();
	}

}
