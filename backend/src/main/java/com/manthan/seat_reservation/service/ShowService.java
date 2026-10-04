package com.manthan.seat_reservation.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.manthan.seat_reservation.domain.SeatLabels;
import com.manthan.seat_reservation.domain.Show;
import com.manthan.seat_reservation.repository.SeatInventoryRepository;
import com.manthan.seat_reservation.repository.ShowRepository;

@Service
public class ShowService {

	public static final int DEFAULT_PER_USER_LIMIT = 4;

	/** The created show and its seat labels. Every seat is inserted as available. */
	public record CreatedShow(Show show, List<String> seatLabels) {
	}

	private final ShowRepository showRepository;
	private final SeatInventoryRepository seatInventory;
	private final int maxSeats;

	public ShowService(ShowRepository showRepository, SeatInventoryRepository seatInventory,
			@Value("${app.shows.max-seats:5000}") int maxSeats) {
		this.showRepository = showRepository;
		this.seatInventory = seatInventory;
		this.maxSeats = maxSeats;
	}

	/** The show and all its seats are created in one transaction, so a failure leaves no partial show. */
	@Transactional
	public CreatedShow createShow(String name, long pricePaise, Integer perUserLimit, List<String> requestedSeats) {
		List<String> seatLabels = SeatLabels.normalize(requestedSeats);
		validateSeats(seatLabels);

		Show show = new Show();
		show.setName(name.trim());
		show.setPricePaise(pricePaise);
		show.setPerUserLimit(perUserLimit == null ? DEFAULT_PER_USER_LIMIT : perUserLimit);
		show.setTotalSeats(seatLabels.size());
		Show saved;
		try {
			// IDENTITY ids make this INSERT run immediately. The unique key uk_shows_name is the only guard, so two
			// concurrent creates of the same name cannot both win. We rethrow at once, never continue in this transaction.
			saved = showRepository.save(show);
		}
		catch (DataIntegrityViolationException e) {
			if (isShowNameViolation(e)) {
				throw new ShowNameTakenException(show.getName());
			}
			throw e;
		}

		seatInventory.insertSeats(saved.getId(), seatLabels);
		return new CreatedShow(saved, seatLabels);
	}

	private static boolean isShowNameViolation(DataIntegrityViolationException e) {
		Throwable root = e.getMostSpecificCause();
		return root.getMessage() != null && root.getMessage().contains("uk_shows_name");
	}

	/** Labels are already uppercase here, so a1 and A1 count as the same seat and the second one is a duplicate. */
	private void validateSeats(List<String> seatLabels) {
		if (seatLabels.size() > maxSeats) {
			throw new InvalidRequestException("seats: " + seatLabels.size() + " seats requested, the maximum is " + maxSeats + " per show");
		}
		Set<String> seen = new HashSet<>();
		for (String label : seatLabels) {
			if (!seen.add(label)) {
				throw new InvalidRequestException("seats: duplicate seat '" + label + "' (labels are case-insensitive)");
			}
		}
	}

}
