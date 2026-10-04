package com.manthan.seat_reservation.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.manthan.seat_reservation.auth.Principal;
import com.manthan.seat_reservation.auth.TokenAuthenticator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The one place that handles request tracing. It runs first on every request and:
 * <ul>
 * <li>picks the trace id and returns it in the X-Trace-Id header on every response, errors included;</li>
 * <li>puts trace_id (and user_id, read from the verified token) on every log line of the request;</li>
 * <li>writes one summary line per request;</li>
 * <li>cleans up afterwards.</li>
 * </ul>
 * Because of this, the rest of the code logs with a plain {@code log.info("...")} and nothing else.
 * An X-Trace-Id (or W3C traceparent) sent by the client is used only if it matches {@code ^[A-Za-z0-9-]{8,64}$},
 * so it cannot be used to inject fake log lines. Otherwise a UUID is generated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Trace-Id";

	private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

	private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
	/** W3C traceparent is version-traceid-parentid-flags. Only the 32 hex trace id is used. */
	private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
	private static final String ALL_ZEROS = "0".repeat(32);

	private final TokenAuthenticator authenticator;

	public TraceIdFilter(TokenAuthenticator authenticator) {
		this.authenticator = authenticator;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long started = System.nanoTime();
		String traceId = resolveTraceId(request);
		MDC.put("trace_id", traceId);
		// Only a token whose signature and expiry check out gives a user id. The token itself is never logged.
		authenticator.authenticate(request).map(Principal::userId).ifPresent(userId -> MDC.put("user_id", userId));
		response.setHeader(HEADER, traceId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			long millis = (System.nanoTime() - started) / 1_000_000;
			// The path only, never the query string.
			if (isHealthProbe(request.getRequestURI())) {
				log.debug("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), millis);
			}
			else {
				log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), millis);
			}
			MDC.remove("trace_id");
			MDC.remove("user_id");
		}
	}

	/** Platforms call these every few seconds, so their summary line is DEBUG to keep the logs readable. */
	private static boolean isHealthProbe(String path) {
		return path.equals("/healthz") || path.equals("/readyz") || path.startsWith("/actuator/health");
	}

	static String resolveTraceId(HttpServletRequest request) {
		String fromHeader = request.getHeader(HEADER);
		if (fromHeader != null && VALID_TRACE_ID.matcher(fromHeader).matches()) {
			return fromHeader;
		}
		String traceparent = request.getHeader("traceparent");
		if (traceparent != null) {
			Matcher matcher = TRACEPARENT.matcher(traceparent);
			if (matcher.matches() && !ALL_ZEROS.equals(matcher.group(1))) {
				return matcher.group(1);
			}
		}
		return UUID.randomUUID().toString();
	}

}
