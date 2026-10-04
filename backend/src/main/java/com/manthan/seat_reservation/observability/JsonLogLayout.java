package com.manthan.seat_reservation.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;

/**
 * Gives the Loki appender the same JSON line that goes to stdout and the daily file, so a log looks identical
 * everywhere. All the formatting lives in {@link JsonLogFormatter}.
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

	private final JsonLogFormatter formatter = new JsonLogFormatter();

	@Override
	public String doLayout(ILoggingEvent event) {
		return formatter.format(event).stripTrailing();
	}

}
