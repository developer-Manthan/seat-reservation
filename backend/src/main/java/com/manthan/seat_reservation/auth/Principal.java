package com.manthan.seat_reservation.auth;

/**
 * The authenticated caller, stored as the request attribute {@value #ATTRIBUTE}. Admin tokens carry no user id,
 * so {@code userId} is null for {@link Role#ADMIN} and admins cannot reserve or cancel seats.
 */
public record Principal(Role role, String userId) {

	public static final String ATTRIBUTE = "principal";

	public static Principal admin() {
		return new Principal(Role.ADMIN, null);
	}

	public static Principal user(String userId) {
		return new Principal(Role.USER, userId);
	}

}
