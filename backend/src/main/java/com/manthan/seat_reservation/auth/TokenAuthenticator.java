package com.manthan.seat_reservation.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns the {@code Authorization: Bearer <token>} header into a {@link Principal}. The admin token is compared
 * with {@code MessageDigest.isEqual}, user tokens are verified by {@link TokenService}. Anything else is empty.
 * Used by the interceptor (API endpoints) and the actuator filter. Never logs the header or the token.
 */
@Component
public class TokenAuthenticator {

	private static final String BEARER = "Bearer ";

	private final TokenService tokenService;
	private final byte[] adminToken;

	public TokenAuthenticator(TokenService tokenService, AuthProperties properties) {
		this.tokenService = tokenService;
		this.adminToken = properties.adminToken() == null ? new byte[0] : properties.adminToken().getBytes(StandardCharsets.UTF_8);
	}

	public Optional<Principal> authenticate(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith(BEARER)) {
			return Optional.empty();
		}
		String token = header.substring(BEARER.length()).trim();
		if (token.isEmpty()) {
			return Optional.empty();
		}
		if (adminToken.length > 0 && MessageDigest.isEqual(adminToken, token.getBytes(StandardCharsets.UTF_8))) {
			return Optional.of(Principal.admin());
		}
		return tokenService.verify(token).map(Principal::user);
	}

}
