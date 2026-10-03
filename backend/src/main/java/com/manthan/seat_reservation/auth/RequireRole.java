package com.manthan.seat_reservation.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Role needed to call an endpoint. The method annotation wins over the class annotation, and an endpoint with
 * neither needs {@link Role#USER}, which means any authenticated caller. Every endpoint is protected by default.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RequireRole {

	Role value();

}
