package com.manthan.seat_reservation.auth;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.manthan.seat_reservation.api.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Metrics are not public. MVC interceptors do not run for Actuator endpoints, so this filter requires the admin
 * token on everything under /actuator/ except health and readiness, which stay open. The response uses the same
 * JSON error shape as the rest of the API.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActuatorAuthFilter extends OncePerRequestFilter {

	private final TokenAuthenticator authenticator;
	private final ObjectMapper objectMapper;

	public ActuatorAuthFilter(TokenAuthenticator authenticator, ObjectMapper objectMapper) {
		this.authenticator = authenticator;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !path.startsWith("/actuator") || path.equals("/actuator/health") || path.startsWith("/actuator/health/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<Principal> principal = authenticator.authenticate(request);
		if (principal.isEmpty()) {
			reject(response, 401, "unauthorized", "Missing or invalid token");
		}
		else if (principal.get().role() != Role.ADMIN) {
			reject(response, 403, "forbidden", "This endpoint needs the admin role");
		}
		else {
			chain.doFilter(request, response);
		}
	}

	private void reject(HttpServletResponse response, int status, String error, String message) throws IOException {
		response.setStatus(status);
		if (status == 401) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(error, message, MDC.get("trace_id")));
	}

}
