package com.manthan.seat_reservation.observability;

import com.github.loki4j.logback.Loki4jAppender;

import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Sends log lines to Grafana Loki, but only when LOKI_URL is set (for example http://loki.railway.internal:3100).
 * With no LOKI_URL it stays switched off and does nothing, so local runs and tests need no Loki and no extra setting.
 */
public class OptionalLokiAppender extends Loki4jAppender {

	private String lokiUrl;

	/** The base address of Loki, without a path. Empty means "do not send to Loki". */
	public void setLokiUrl(String lokiUrl) {
		this.lokiUrl = lokiUrl == null ? "" : lokiUrl.trim();
	}

	@Override
	public void start() {
		if (lokiUrl == null || !lokiUrl.startsWith("http")) {
			addInfo("LOKI_URL is not set, logs are not sent to Loki");
			return;
		}
		HttpCfg http = new HttpCfg();
		http.setUrl(lokiUrl.replaceAll("/+$", "") + "/loki/api/v1/push");
		setHttp(http);
		super.start();
	}

	/** When switched off, ignore lines quietly instead of warning about an appender that was never started. */
	@Override
	public void doAppend(ILoggingEvent event) {
		if (isStarted()) {
			super.doAppend(event);
		}
	}

}
