package com.manthan.seat_reservation.domain;

import java.util.List;
import java.util.Locale;

/**
 * Seat labels are case-insensitive on input and always stored in uppercase (a1 and A1 are the same seat).
 * Every place that accepts a label (show creation, reserve) must normalize it with this class.
 */
public final class SeatLabels {

	private SeatLabels() {
	}

	public static String normalize(String label) {
		return label.toUpperCase(Locale.ROOT);
	}

	public static List<String> normalize(List<String> labels) {
		return labels.stream().map(SeatLabels::normalize).toList();
	}

}
