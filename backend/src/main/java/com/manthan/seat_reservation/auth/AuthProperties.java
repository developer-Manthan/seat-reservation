package com.manthan.seat_reservation.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.auth.*}. The secret and the admin token come from the environment (TOKEN_SECRET, ADMIN_TOKEN).
 * Never log this object or its values.
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(String tokenSecret, String adminToken, long tokenTtlSeconds) {

	public static final String DEV_TOKEN_SECRET = "dev-only-secret";
	public static final String DEV_ADMIN_TOKEN = "dev-only-admin-token";

	@Override
	public String toString() {
		return "AuthProperties[tokenTtlSeconds=" + tokenTtlSeconds + "]";
	}

}
