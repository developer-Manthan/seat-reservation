package com.manthan.seat_reservation.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.manthan.seat_reservation.auth.ForbiddenException;
import com.manthan.seat_reservation.auth.UnauthorizedException;
import com.manthan.seat_reservation.observability.ReservationMetrics;
import com.manthan.seat_reservation.service.DbFailures;
import com.manthan.seat_reservation.service.IdempotencyConflictException;
import com.manthan.seat_reservation.service.InvalidRequestException;
import com.manthan.seat_reservation.service.PerUserLimitException;
import com.manthan.seat_reservation.service.SeatTakenException;
import com.manthan.seat_reservation.service.ServiceBusyException;
import com.manthan.seat_reservation.service.ShowNameTakenException;
import com.manthan.seat_reservation.service.ShowNotFoundException;

/**
 * Maps every known failure to the standard JSON error shape. Spring's own MVC exceptions (bad body, wrong
 * method, unknown path, ...) keep their 4xx status through ResponseEntityExceptionHandler. Anything unexpected is
 * logged at ERROR with its stack trace and returned as a generic 500 without internal details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final ReservationMetrics metrics;
	private final int retryAfterSeconds;

	public GlobalExceptionHandler(ReservationMetrics metrics, @Value("${app.retry-after-seconds:1}") int retryAfterSeconds) {
		this.metrics = metrics;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	@ExceptionHandler(UnauthorizedException.class)
	ResponseEntity<ErrorResponse> unauthorized(UnauthorizedException e) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.body(body("unauthorized", e.getMessage()));
	}

	@ExceptionHandler(ForbiddenException.class)
	ResponseEntity<ErrorResponse> forbidden(ForbiddenException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body("forbidden", e.getMessage()));
	}

	@ExceptionHandler(SeatTakenException.class)
	ResponseEntity<ErrorResponse> seatTaken(SeatTakenException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(body("seat-taken", e.getMessage()));
	}

	@ExceptionHandler(PerUserLimitException.class)
	ResponseEntity<ErrorResponse> perUserLimit(PerUserLimitException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(body("per-user-limit", e.getMessage()));
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	ResponseEntity<ErrorResponse> idempotencyConflict(IdempotencyConflictException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(body("idempotency-conflict", e.getMessage()));
	}

	@ExceptionHandler(ShowNotFoundException.class)
	ResponseEntity<ErrorResponse> showNotFound(ShowNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not-found", e.getMessage()));
	}

	@ExceptionHandler(ShowNameTakenException.class)
	ResponseEntity<ErrorResponse> showNameTaken(ShowNameTakenException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(body("show-name-taken", e.getMessage()));
	}

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ErrorResponse> invalidRequest(InvalidRequestException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("bad-request", e.getMessage()));
	}

	@ExceptionHandler(ServiceBusyException.class)
	ResponseEntity<ErrorResponse> serviceBusy(ServiceBusyException e) {
		return tooManyRequests(e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected(Exception e) {
		// Pool exhaustion can surface from any endpoint (even the interceptor), wrapped in several exceptions.
		if (DbFailures.isPoolTimeout(e)) {
			return tooManyRequests("The database is busy, retry shortly");
		}
		log.error("Unexpected exception", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(body("internal-error", "Unexpected error"));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(f -> snakeCase(f.getField()) + ": " + f.getDefaultMessage())
				.findFirst().orElse("Invalid request");
		return ResponseEntity.status(status).headers(headers).body(body("bad-request", message));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		String error = statusCode instanceof HttpStatus s ? s.name().toLowerCase().replace('_', '-') : "error";
		String message = statusCode.is4xxClientError() ? "Bad request" : "Unexpected error";
		return ResponseEntity.status(statusCode).headers(headers).body(body(error, message));
	}

	private ResponseEntity<ErrorResponse> tooManyRequests(String message) {
		metrics.throttled();
		log.warn("Responding 429: {}", message);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
				.body(body("too-many-requests", message));
	}

	/** Field errors name the Java property (userId), the API speaks snake_case (user_id). */
	private static String snakeCase(String field) {
		return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}

	private static ErrorResponse body(String error, String message) {
		return new ErrorResponse(error, message, MDC.get("trace_id"));
	}

}
