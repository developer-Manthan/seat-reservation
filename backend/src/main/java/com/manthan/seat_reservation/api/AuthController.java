package com.manthan.seat_reservation.api;

import java.time.Instant;
import java.util.Locale;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.manthan.seat_reservation.auth.TokenService;
import com.manthan.seat_reservation.service.UserRegistrar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** The only endpoint that needs no token. The user id is a UUID and is stored in lowercase. */
@RestController
public class AuthController {

	private final TokenService tokenService;
	private final UserRegistrar userRegistrar;

	public AuthController(TokenService tokenService, UserRegistrar userRegistrar) {
		this.tokenService = tokenService;
		this.userRegistrar = userRegistrar;
	}

	public record TokenRequest(
			@JsonProperty("user_id")
			@NotNull(message = "is required")
			@Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
					message = "must be a UUID")
			String userId) {
	}

	public record TokenResponse(String token, @JsonProperty("user_id") String userId,
			@JsonProperty("expires_at") Instant expiresAt) {
	}

	@PostMapping("/auth/token")
	public TokenResponse token(@Valid @RequestBody TokenRequest request) {
		String userId = request.userId().toLowerCase(Locale.ROOT);
		userRegistrar.ensureUser(userId);
		Instant expiresAt = tokenService.expiryFromNow();
		return new TokenResponse(tokenService.issue(userId, expiresAt), userId, expiresAt);
	}

}
