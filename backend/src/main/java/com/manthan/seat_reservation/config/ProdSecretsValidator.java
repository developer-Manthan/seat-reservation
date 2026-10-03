package com.manthan.seat_reservation.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.manthan.seat_reservation.auth.AuthProperties;

/** In production the app refuses to start without real secrets. The values are never put in the message. */
@Component
@Profile("prod")
public class ProdSecretsValidator implements InitializingBean {

	private final AuthProperties properties;

	public ProdSecretsValidator(AuthProperties properties) {
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		check("TOKEN_SECRET", properties.tokenSecret(), AuthProperties.DEV_TOKEN_SECRET);
		check("ADMIN_TOKEN", properties.adminToken(), AuthProperties.DEV_ADMIN_TOKEN);
	}

	private static void check(String name, String value, String devDefault) {
		if (value == null || value.isBlank() || value.equals(devDefault)) {
			throw new IllegalStateException(name + " is missing or still the dev default. Set a real secret in the environment.");
		}
	}

}
