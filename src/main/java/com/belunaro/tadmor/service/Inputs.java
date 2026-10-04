package com.belunaro.tadmor.service;

/**
 * Normalizing and checking request values, shared by the services.
 *
 * <p>Request records call the normalizers from their compact constructors,
 * so a service only ever sees normalized input: required text trimmed (null
 * becomes ""), an omitted boolean false (spec/api.md §1.3: an update is a
 * full replacement), and an omitted decimal its documented default.
 * Decimals stay strings all the way to the database, which casts them to
 * the column's numeric type, so they never pass through binary floating
 * point; Postgres rounds them to the column's scale, half away from zero,
 * and refuses malformed or out-of-range values (spec/api.md §1.2), which
 * DatabaseErrors turns into 422.
 */
public final class Inputs {

	private Inputs() {
	}

	/** A text field, trimmed; null becomes "". */
	public static String trim(String s) {
		return s == null ? "" : s.strip();
	}

	/** A boolean, false when omitted. */
	public static Boolean flag(Boolean b) {
		return b != null && b;
	}

	/** A decimal string, or the default when omitted or empty. */
	public static String decimal(String s, String orElse) {
		return s == null || s.isBlank() ? orElse : s.strip();
	}

	/** 400 unless the (normalized) field has a value. */
	public static void require(String value, String field) {
		if (value == null || value.isEmpty()) {
			throw ServiceException.badRequest(field + " is required");
		}
	}
}
