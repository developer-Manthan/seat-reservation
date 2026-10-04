package com.manthan.seat_reservation.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Seat labels are case-insensitive on input and always stored in uppercase (a1 and A1 are the same seat).
 * Every place that accepts a label (show creation, reserve) must normalize it with this class.
 */
public final class SeatLabels {

	/** Natural order for display: A1, A2, A10 (not A1, A10, A2). Digit runs compare by value, ties fall back to plain order. */
	public static final Comparator<String> NATURAL_ORDER = SeatLabels::compareNaturally;

	private SeatLabels() {
	}

	public static String normalize(String label) {
		return label.toUpperCase(Locale.ROOT);
	}

	public static List<String> normalize(List<String> labels) {
		return labels.stream().map(SeatLabels::normalize).toList();
	}

	private static int compareNaturally(String a, String b) {
		int i = 0;
		int j = 0;
		while (i < a.length() && j < b.length()) {
			boolean digitsA = Character.isDigit(a.charAt(i));
			boolean digitsB = Character.isDigit(b.charAt(j));
			int endA = runEnd(a, i, digitsA);
			int endB = runEnd(b, j, digitsB);
			String runA = a.substring(i, endA);
			String runB = b.substring(j, endB);
			int result;
			if (digitsA && digitsB) {
				result = compareNumbers(runA, runB);
			}
			else if (digitsA != digitsB) {
				result = digitsA ? -1 : 1;
			}
			else {
				result = runA.compareTo(runB);
			}
			if (result != 0) {
				return result;
			}
			i = endA;
			j = endB;
		}
		int remaining = Integer.compare(a.length() - i, b.length() - j);
		return remaining != 0 ? remaining : a.compareTo(b);
	}

	private static int runEnd(String s, int from, boolean digits) {
		int end = from;
		while (end < s.length() && Character.isDigit(s.charAt(end)) == digits) {
			end++;
		}
		return end;
	}

	/** Labels are at most 32 characters, so a digit run can exceed a long: compare without parsing. */
	private static int compareNumbers(String a, String b) {
		String x = a.replaceFirst("^0+(?=.)", "");
		String y = b.replaceFirst("^0+(?=.)", "");
		return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
	}

}
