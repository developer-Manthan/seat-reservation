package com.manthan.seat_reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.manthan.seat_reservation.auth.AuthProperties;
import com.manthan.seat_reservation.auth.TokenService;

class TokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
	private static final long DAY = Duration.ofHours(24).toSeconds();

	private final String userId = UUID.randomUUID().toString();

	private TokenService serviceAt(Instant now, String secret) {
		return new TokenService(new AuthProperties(secret, "admin", DAY), Clock.fixed(now, ZoneOffset.UTC));
	}

	@Test
	void issuedTokenVerifiesBackToTheUserId() {
		TokenService service = serviceAt(NOW, "secret-1");
		String token = service.issue(userId, service.expiryFromNow());

		assertThat(service.verify(token)).contains(userId);
	}

	@Test
	void tokenLivesForTwentyFourHours() {
		TokenService issuer = serviceAt(NOW, "secret-1");
		assertThat(issuer.expiryFromNow()).isEqualTo(NOW.plus(Duration.ofHours(24)));
		String token = issuer.issue(userId, issuer.expiryFromNow());

		assertThat(serviceAt(NOW.plusSeconds(DAY - 1), "secret-1").verify(token)).contains(userId);
		assertThat(serviceAt(NOW.plusSeconds(DAY), "secret-1").verify(token)).isEmpty();
		assertThat(serviceAt(NOW.plusSeconds(DAY + 3600), "secret-1").verify(token)).isEmpty();
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		TokenService other = serviceAt(NOW, "secret-2");
		String token = other.issue(userId, other.expiryFromNow());

		assertThat(serviceAt(NOW, "secret-1").verify(token)).isEmpty();
	}

	@Test
	void tamperedPayloadOrSignatureIsRejected() {
		TokenService service = serviceAt(NOW, "secret-1");
		String token = service.issue(userId, service.expiryFromNow());
		String[] parts = token.split("\\.");

		String otherUserToken = service.issue(UUID.randomUUID().toString(), service.expiryFromNow());
		String swappedPayload = otherUserToken.split("\\.")[0] + "." + parts[1];
		String flippedSignature = parts[0] + "." + flipFirstChar(parts[1]);

		assertThat(service.verify(swappedPayload)).isEmpty();
		assertThat(service.verify(flippedSignature)).isEmpty();
	}

	@Test
	void garbageNeverThrows() {
		TokenService service = serviceAt(NOW, "secret-1");

		for (String bad : new String[] { null, "", ".", "a.b", "a.b.c", "abc", "....", "%%%.%%%", "e30.e30", "Zm9v." }) {
			assertThat(service.verify(bad)).as("token '%s'", bad).isEmpty();
		}
	}

	@Test
	void userIdThatIsNotACanonicalUuidIsRejectedEvenWhenSigned() {
		TokenService service = serviceAt(NOW, "secret-1");
		String token = service.issue("not-a-uuid", service.expiryFromNow());

		assertThat(service.verify(token)).isEmpty();
	}

	private static String flipFirstChar(String s) {
		return (s.charAt(0) == 'A' ? 'B' : 'A') + s.substring(1);
	}

}
