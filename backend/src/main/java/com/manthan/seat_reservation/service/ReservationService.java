package com.manthan.seat_reservation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.manthan.seat_reservation.domain.IdempotencyKey;
import com.manthan.seat_reservation.domain.Reservation;
import com.manthan.seat_reservation.domain.ReservationMode;
import com.manthan.seat_reservation.domain.ReservationStatus;
import com.manthan.seat_reservation.domain.SeatLabels;
import com.manthan.seat_reservation.domain.Show;
import com.manthan.seat_reservation.observability.ReservationMetrics;
import com.manthan.seat_reservation.repository.DataInvariantException;
import com.manthan.seat_reservation.repository.SeatStore;
import com.manthan.seat_reservation.repository.ShowRepository;
import com.manthan.seat_reservation.service.ReserveTransaction.Attempt;
import com.manthan.seat_reservation.service.ReserveTransaction.Booked;
import com.manthan.seat_reservation.service.ReserveTransaction.KeyExists;
import com.manthan.seat_reservation.service.ReserveTransaction.Outcome;

/**
 * Orchestrates a reserve request. This bean is NOT transactional: it validates, reads the show, takes the idempotency
 * fast path, calls the transactional {@link ReserveTransaction} (a separate bean, so a retry loop can wrap that call
 * later), and turns the outcome into a result. Metrics are recorded here, after the transaction has ended.
 */
@Service
public class ReservationService {

	private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

	public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

	/** The booked seats are sorted. {@code unavailableSeats} is null unless a best_effort request missed some. */
	public record ReservationResult(String reservationId, long showId, ReservationStatus status, long amountPaise,
			List<String> seats, List<String> unavailableSeats) {
	}

	private final ShowRepository showRepository;
	private final SeatStore store;
	private final ReserveTransaction reserveTransaction;
	private final LockRetrier retrier;
	private final ReservationMetrics metrics;
	private final ReservationMode defaultMode;

	public ReservationService(ShowRepository showRepository, SeatStore store, ReserveTransaction reserveTransaction,
			LockRetrier retrier, ReservationMetrics metrics, @Value("${app.reservation.default-mode:all_or_nothing}") String defaultMode) {
		this.showRepository = showRepository;
		this.store = store;
		this.reserveTransaction = reserveTransaction;
		this.retrier = retrier;
		this.metrics = metrics;
		// An invalid configured default fails the startup instead of failing every request.
		this.defaultMode = parseMode(defaultMode).orElseThrow(() -> new IllegalStateException(
				"app.reservation.default-mode must be all_or_nothing or best_effort, was '" + defaultMode + "'"));
	}

	public ReservationResult reserve(String userId, long showId, List<String> requestedSeats, String idempotencyKey,
			String modeText) {
		// 1. Validate. Normalize, dedupe and sort the labels (sorted order is the lock order everywhere).
		ReservationMode mode = modeText == null ? defaultMode : parseMode(modeText)
				.orElseThrow(() -> new InvalidRequestException("mode: must be all_or_nothing or best_effort"));
		if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
			throw new InvalidRequestException("idempotency_key: must be 1 to 128 letters, digits, '.', '_', ':' or '-'");
		}
		List<String> seats = List.copyOf(new TreeSet<>(SeatLabels.normalize(requestedSeats)));
		if (seats.isEmpty()) {
			throw new InvalidRequestException("seats: at least one seat is required");
		}
		Show show = showRepository.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
		if (seats.size() > show.getPerUserLimit()) {
			throw new InvalidRequestException("seats: " + seats.size() + " distinct seats requested, the limit is "
					+ show.getPerUserLimit() + " per user for this show");
		}
		String requestHash = requestHash(showId, mode, seats);

		// 0. Fast path (outside any transaction). Only an optimization, the primary key is the real guard.
		Optional<IdempotencyKey> existing = store.findIdempotencyKey(userId, idempotencyKey);
		if (existing.isPresent()) {
			return replay(userId, existing.get().getRequestHash(), existing.get().getReservationId(), requestHash, seats, mode);
		}

		Outcome outcome;
		try {
			Attempt attempt = new Attempt(userId, showId, show.getPricePaise(), show.getPerUserLimit(), seats, idempotencyKey,
					requestHash, mode);
			// The retry loop wraps the transactional call from outside, so every attempt is a fresh transaction.
			outcome = retrier.run(() -> reserveTransaction.reserveOnce(attempt));
		}
		catch (SeatTakenException e) {
			metrics.declined(ReservationMetrics.SEAT_TAKEN);
			log.info("Reservation declined: seat-taken user={} show={} seats={} mode={}", userId, showId, e.getSeats(), mode);
			throw e;
		}
		catch (PerUserLimitException e) {
			metrics.declined(ReservationMetrics.PER_USER_LIMIT);
			log.info("Reservation declined: per-user-limit user={} show={} requested={}", userId, showId, seats.size());
			throw e;
		}

		if (outcome instanceof KeyExists keyExists) {
			// The provisional reservation was rolled back. This is a replay (or a conflict), never an error.
			return replay(userId, keyExists.storedRequestHash(), keyExists.reservationId(), requestHash, seats, mode);
		}
		Booked booked = (Booked) outcome;
		metrics.confirmed();
		if (!booked.unavailableSeats().isEmpty()) {
			metrics.partial();
			log.info("Reservation partially booked: user={} show={} reservation={} booked={} unavailable={}", userId, showId,
					booked.reservationId(), booked.bookedSeats(), booked.unavailableSeats());
		}
		else {
			log.info("Reservation confirmed: user={} show={} reservation={} seats={} amount_paise={}", userId, showId,
					booked.reservationId(), booked.bookedSeats(), booked.amountPaise());
		}
		return new ReservationResult(booked.reservationId(), showId, ReservationStatus.confirmed, booked.amountPaise(),
				booked.bookedSeats(), booked.unavailableSeats().isEmpty() ? null : booked.unavailableSeats());
	}

	/** Same hash returns the original reservation as it is now. A different hash is a conflict. */
	private ReservationResult replay(String userId, String storedHash, String reservationId, String requestHash,
			List<String> requestedSeats, ReservationMode mode) {
		if (!storedHash.equals(requestHash)) {
			metrics.declined(ReservationMetrics.IDEMPOTENCY_CONFLICT);
			log.info("Reservation declined: idempotency-conflict user={} reservation={}", userId, reservationId);
			throw new IdempotencyConflictException();
		}
		Reservation reservation = store.findReservation(reservationId).orElseThrow(
				() -> new DataInvariantException("Idempotency key points at missing reservation " + reservationId));
		List<String> booked = store.findSeatLabels(reservationId);
		List<String> unavailable = null;
		if (mode == ReservationMode.best_effort && reservation.getStatus() == ReservationStatus.confirmed) {
			List<String> missed = new ArrayList<>(requestedSeats);
			missed.removeAll(booked);
			unavailable = missed.isEmpty() ? null : missed;
		}
		metrics.declined(ReservationMetrics.IDEMPOTENT_REPLAY);
		log.info("Reservation replayed: user={} reservation={} status={}", userId, reservationId, reservation.getStatus());
		return new ReservationResult(reservationId, reservation.getShowId(), reservation.getStatus(),
				reservation.getAmountPaise(), booked, unavailable);
	}

	private static Optional<ReservationMode> parseMode(String text) {
		for (ReservationMode mode : ReservationMode.values()) {
			if (mode.name().equals(text)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}

	/** SHA-256 over the show, the mode and the sorted seats. The same request always gives the same hash. */
	static String requestHash(long showId, ReservationMode mode, List<String> sortedSeats) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String canonical = showId + "\n" + mode.name() + "\n" + String.join(",", sortedSeats);
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

}
