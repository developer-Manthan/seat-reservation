package com.manthan.seat_reservation.service;

/** A show with this name already exists (names are unique, ignoring case): 409 show-name-taken. */
public class ShowNameTakenException extends RuntimeException {

	public ShowNameTakenException(String name) {
		super("A show named '" + name + "' already exists (names are case-insensitive)");
	}

}
