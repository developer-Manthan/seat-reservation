package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.manthan.seat_reservation.auth.AuthProperties;
import com.manthan.seat_reservation.config.ProdSecretsValidator;

class ProdSecretsValidatorTest {

	private static void validate(String secret, String admin) {
		new ProdSecretsValidator(new AuthProperties(secret, admin, 86400)).afterPropertiesSet();
	}

	@Test
	void realSecretsAreAccepted() {
		assertThatCode(() -> validate("a-real-long-random-secret", "a-real-admin-token")).doesNotThrowAnyException();
	}

	@Test
	void devDefaultsAreRefused() {
		assertThatThrownBy(() -> validate("dev-only-secret", "a-real-admin-token"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("TOKEN_SECRET");
		assertThatThrownBy(() -> validate("a-real-long-random-secret", "dev-only-admin-token"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_TOKEN");
	}

	@Test
	void missingOrBlankSecretsAreRefused() {
		assertThatThrownBy(() -> validate(null, "a-real-admin-token")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> validate("a-real-long-random-secret", "  ")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theMessageNeverContainsTheSecretValue() {
		assertThatThrownBy(() -> validate("dev-only-secret", "a-real-admin-token"))
				.hasMessageNotContaining("dev-only-secret");
	}

}
