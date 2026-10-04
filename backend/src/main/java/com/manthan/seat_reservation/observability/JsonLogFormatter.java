package com.manthan.seat_reservation.observability;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.logging.structured.StructuredLogFormatter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides what one log line looks like. Every line is one JSON object with, in this order:
 * timestamp, level, file, function, message, then whatever the request added (trace_id, user_id, attempt),
 * and stack_trace when an exception was logged.
 * file and function are where the log call was written, for example ReservationService.java and reserve.
 * To add or remove a field on every line, this is the only place to change.
 */
public class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Override
	public String format(ILoggingEvent event) {
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("timestamp", OffsetDateTime.ofInstant(event.getInstant(), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		line.put("level", event.getLevel().toString());
		StackTraceElement[] caller = event.getCallerData();
		if (caller != null && caller.length > 0) {
			line.put("file", caller[0].getFileName());
			line.put("function", functionName(caller[0].getMethodName()));
		}
		line.put("message", event.getFormattedMessage());
		line.putAll(event.getMDCPropertyMap());
		if (event.getThrowableProxy() != null) {
			line.put("stack_trace", ThrowableProxyUtil.asString(event.getThrowableProxy()));
		}
		return JSON.writeValueAsString(line) + "\n";
	}

	/** A log call inside a lambda is reported as lambda$reserve$0. Show the method it was written in: reserve. */
	private static String functionName(String method) {
		if (method != null && method.startsWith("lambda$")) {
			int end = method.indexOf('$', "lambda$".length());
			return end > 0 ? method.substring("lambda$".length(), end) : method;
		}
		return method;
	}

}
