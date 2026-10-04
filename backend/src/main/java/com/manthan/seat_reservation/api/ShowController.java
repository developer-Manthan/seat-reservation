package com.manthan.seat_reservation.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.manthan.seat_reservation.auth.RequireRole;
import com.manthan.seat_reservation.auth.Role;
import com.manthan.seat_reservation.domain.Show;
import com.manthan.seat_reservation.domain.SeatStatus;
import com.manthan.seat_reservation.service.ShowService;
import com.manthan.seat_reservation.service.ShowService.CreatedShow;
import com.manthan.seat_reservation.service.ShowService.ShowView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@RestController
public class ShowController {

	private final ShowService showService;

	public ShowController(ShowService showService) {
		this.showService = showService;
	}

	public record CreateShowRequest(
			@NotBlank(message = "is required") @Size(max = 255, message = "must be at most 255 characters") String name,
			@JsonProperty("price_paise") @NotNull(message = "is required") @PositiveOrZero(message = "must not be negative") Long pricePaise,
			@JsonProperty("per_user_limit") @Min(value = 1, message = "must be at least 1") Integer perUserLimit,
			@NotEmpty(message = "is required") List<@NotNull(message = "must not be null")
					@Pattern(regexp = "^[A-Za-z0-9._-]{1,32}$", message = "must be 1 to 32 letters, digits, '.', '_' or '-'") String> seats) {
	}

	public record SeatView(@JsonProperty("seat_label") String seatLabel, SeatStatus status) {
	}

	public record ShowResponse(Long id, String name, @JsonProperty("price_paise") long pricePaise,
			@JsonProperty("per_user_limit") int perUserLimit, @JsonProperty("total_seats") int totalSeats,
			List<SeatView> seats) {

		/** Every seat of a freshly created show is available. */
		static ShowResponse of(CreatedShow created) {
			Show show = created.show();
			List<SeatView> seats = created.seatLabels().stream()
					.map(label -> new SeatView(label, SeatStatus.available)).toList();
			return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(), show.getPerUserLimit(),
					show.getTotalSeats(), seats);
		}

	}

	public record Counts(int available, int held, int confirmed) {
	}

	public record ShowDetailResponse(Long id, String name, @JsonProperty("price_paise") long pricePaise,
			@JsonProperty("per_user_limit") int perUserLimit, @JsonProperty("total_seats") int totalSeats,
			Counts counts, List<SeatView> seats) {

		static ShowDetailResponse of(ShowView view) {
			Show show = view.show();
			Counts counts = new Counts(view.counts().get(SeatStatus.available), view.counts().get(SeatStatus.held),
					view.counts().get(SeatStatus.confirmed));
			List<SeatView> seats = view.seats().stream().map(row -> new SeatView(row.getSeatLabel(), row.getStatus())).toList();
			return new ShowDetailResponse(show.getId(), show.getName(), show.getPricePaise(), show.getPerUserLimit(),
					show.getTotalSeats(), counts, seats);
		}

	}

	/** Any authenticated caller (user or admin) can read a show. Seats come in natural order (A1, A2, A10). */
	@GetMapping("/shows/{id}")
	public ShowDetailResponse get(@PathVariable("id") Long id) {
		return ShowDetailResponse.of(showService.getShow(id));
	}

	@PostMapping("/shows")
	@RequireRole(Role.ADMIN)
	public ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
		CreatedShow created = showService.createShow(request.name(), request.pricePaise(), request.perUserLimit(),
				request.seats());
		return ResponseEntity.created(URI.create("/shows/" + created.show().getId())).body(ShowResponse.of(created));
	}

}
