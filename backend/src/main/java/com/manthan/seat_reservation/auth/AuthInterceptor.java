package com.manthan.seat_reservation.auth;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.manthan.seat_reservation.service.UserRegistrar;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates every API request except POST /auth/token (and the open health paths, excluded in AuthConfig).
 * A new endpoint is protected by default. Actuator endpoints are not seen by MVC interceptors, so
 * {@link ActuatorAuthFilter} guards those.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

	private final TokenAuthenticator authenticator;
	private final UserRegistrar userRegistrar;

	public AuthInterceptor(TokenAuthenticator authenticator, UserRegistrar userRegistrar) {
		this.authenticator = authenticator;
		this.userRegistrar = userRegistrar;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (isTokenEndpoint(request)) {
			return true;
		}
		Principal principal = authenticator.authenticate(request)
				.orElseThrow(() -> new UnauthorizedException("Missing or invalid token"));
		if (requiredRole(handler) == Role.ADMIN && principal.role() != Role.ADMIN) {
			throw new ForbiddenException("This endpoint needs the admin role");
		}
		if (principal.role() == Role.USER) {
			userRegistrar.ensureUser(principal.userId());
		}
		request.setAttribute(Principal.ATTRIBUTE, principal);
		return true;
	}

	private boolean isTokenEndpoint(HttpServletRequest request) {
		return "POST".equals(request.getMethod()) && "/auth/token".equals(request.getRequestURI());
	}

	/** Method annotation, then class annotation, default USER (any authenticated caller). */
	private Role requiredRole(Object handler) {
		if (handler instanceof HandlerMethod method) {
			RequireRole onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireRole.class);
			if (onMethod != null) {
				return onMethod.value();
			}
			RequireRole onClass = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRole.class);
			if (onClass != null) {
				return onClass.value();
			}
		}
		return Role.USER;
	}

}
