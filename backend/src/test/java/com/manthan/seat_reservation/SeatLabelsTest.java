package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.manthan.seat_reservation.domain.SeatLabels;

class SeatLabelsTest {

	private static List<String> sorted(String... labels) {
		List<String> list = new ArrayList<>(List.of(labels));
		list.sort(SeatLabels.NATURAL_ORDER);
		return list;
	}

	@Test
	void normalizeUppercasesLabels() {
		assertThat(SeatLabels.normalize("a1")).isEqualTo("A1");
		assertThat(SeatLabels.normalize("vip-3.b")).isEqualTo("VIP-3.B");
		assertThat(SeatLabels.normalize(List.of("a", "B", "c1"))).containsExactly("A", "B", "C1");
	}

	@Test
	void numbersCompareByValueNotAlphabetically() {
		assertThat(sorted("A10", "A2", "A1", "A100", "A20")).containsExactly("A1", "A2", "A10", "A20", "A100");
	}

	@Test
	void rowsGroupTogetherInOrder() {
		assertThat(sorted("B1", "A10", "A2", "B10", "A1", "B2"))
				.containsExactly("A1", "A2", "A10", "B1", "B2", "B10");
	}

	@Test
	void mixedLabelsKeepTheirPrefixTogether() {
		assertThat(sorted("VIP-10", "VIP-2", "A1", "VIP-1", "A", "1", "2", "10"))
				.containsExactly("1", "2", "10", "A", "A1", "VIP-1", "VIP-2", "VIP-10");
	}

	@Test
	void leadingZerosAndHugeNumbersDoNotBreakTheOrder() {
		assertThat(sorted("A1", "A01", "A001")).containsExactly("A001", "A01", "A1");
		String huge = "A" + "9".repeat(31);
		String larger = "B1";
		assertThat(sorted(larger, huge, "A2")).containsExactly("A2", huge, larger);
	}

	@Test
	void orderIsTotalAndStableForAnyShuffle() {
		List<String> expected = sorted("A1", "A2", "A10", "A11", "B1", "B2", "B10", "VIP-1", "VIP-2", "VIP-10", "Z");
		for (int seed = 0; seed < 20; seed++) {
			List<String> shuffled = new ArrayList<>(expected);
			Collections.shuffle(shuffled, new Random(seed));
			shuffled.sort(SeatLabels.NATURAL_ORDER);
			assertThat(shuffled).containsExactlyElementsOf(expected);
		}
	}

}
