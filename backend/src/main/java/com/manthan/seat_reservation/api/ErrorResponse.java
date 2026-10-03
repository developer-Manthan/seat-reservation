package com.manthan.seat_reservation.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The standard JSON error body. {@code trace_id} comes from the MDC (set by the trace filter). */
public record ErrorResponse(String error, String message, @JsonProperty("trace_id") String traceId) {
}
