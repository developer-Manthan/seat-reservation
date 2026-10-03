package com.manthan.seat_reservation.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * Signs and verifies user tokens: {@code base64url(userId|expiryEpochSeconds).base64url(HMAC-SHA256)}.
 * The signature covers the encoded payload and is compared in constant time.
 */
@Service
public class TokenService {

	/** Canonical (lowercase) UUID, the only accepted user id format. */
	public static final Pattern USER_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

	private static final String HMAC = "HmacSHA256";
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final byte[] secret;
	private final long ttlSeconds;
	private final Clock clock;

	public TokenService(AuthProperties properties, Clock clock) {
		this.secret = properties.tokenSecret().getBytes(StandardCharsets.UTF_8);
		this.ttlSeconds = properties.tokenTtlSeconds();
		this.clock = clock;
	}

	public Instant expiryFromNow() {
		return clock.instant().plusSeconds(ttlSeconds);
	}

	public String issue(String userId, Instant expiresAt) {
		String payload = ENCODER.encodeToString((userId + "|" + expiresAt.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
		return payload + "." + ENCODER.encodeToString(sign(payload));
	}

	/** The user id if the token is well formed, correctly signed and not expired. Never throws on bad input. */
	public Optional<String> verify(String token) {
		if (token == null) {
			return Optional.empty();
		}
		int dot = token.indexOf('.');
		if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
			return Optional.empty();
		}
		String payload = token.substring(0, dot);
		try {
			byte[] presented = DECODER.decode(token.substring(dot + 1));
			if (!MessageDigest.isEqual(sign(payload), presented)) {
				return Optional.empty();
			}
			String decoded = new String(DECODER.decode(payload), StandardCharsets.UTF_8);
			int bar = decoded.lastIndexOf('|');
			if (bar <= 0) {
				return Optional.empty();
			}
			String userId = decoded.substring(0, bar);
			long expiresAt = Long.parseLong(decoded.substring(bar + 1));
			if (clock.instant().getEpochSecond() >= expiresAt || !USER_ID.matcher(userId).matches()) {
				return Optional.empty();
			}
			return Optional.of(userId);
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private byte[] sign(String payload) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(new SecretKeySpec(secret, HMAC));
			return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA256 is not available", e);
		}
	}

}
