package com.manthan.seat_reservation.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JacksonConfig {

	/**
	 * Text fields (names, seat labels, ids) accept only JSON strings. By default Jackson turns a number or boolean
	 * into text, so {@code "seats": [1]} would silently become the seat "1". Numbers stay strict through
	 * the spring.jackson properties (no 12.5 as an int, no "100" as a number).
	 */
	@Bean
	JsonMapperBuilderCustomizer strictTextCoercion() {
		return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> {
			config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
			config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
			config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
		});
	}

}
