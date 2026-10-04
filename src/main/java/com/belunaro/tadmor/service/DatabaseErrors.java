package com.belunaro.tadmor.service;

import java.sql.SQLException;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

/**
 * Maps a refusal by the shared schema to the status the spec gives it, by
 * Postgres SQLSTATE, as tadmor does: a unique violation is a 409 (a
 * duplicate key), and a foreign-key, check, not-null, or exclusion
 * violation, an exception raised by a trigger, or a data exception (a
 * malformed or out-of-range decimal or date) is a 422. Anything else is a
 * server fault.
 *
 * <p>Spring's own exception translation is not used for this: it files
 * trigger exceptions (P0001) and exclusion violations under errors it
 * cannot categorize, and several of the schema's rules live in triggers.
 */
public final class DatabaseErrors {

	private DatabaseErrors() {
	}

	public static Optional<ServiceException> refusal(DataAccessException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql && sql.getSQLState() != null) {
				HttpStatus status = status(sql.getSQLState());
				return status == null ? Optional.empty() : Optional.of(new ServiceException(status, message(sql)));
			}
		}
		return Optional.empty();
	}

	private static HttpStatus status(String state) {
		return switch (state) {
		case "23505" -> HttpStatus.CONFLICT;
		case "23503", "23514", "23502", "23P01", "P0001" -> HttpStatus.UNPROCESSABLE_ENTITY;
		default -> state.startsWith("22") ? HttpStatus.UNPROCESSABLE_ENTITY : null;
		};
	}

	/** The server's message, without pgjdbc's "ERROR:" prefix and detail lines. */
	private static String message(SQLException e) {
		String m = e.getMessage() == null ? "" : e.getMessage();
		int newline = m.indexOf('\n');
		if (newline >= 0) {
			m = m.substring(0, newline);
		}
		return m.replaceFirst("^ERROR:\\s*", "").strip();
	}
}
