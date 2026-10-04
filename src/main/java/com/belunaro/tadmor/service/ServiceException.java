package com.belunaro.tadmor.service;

import org.springframework.http.HttpStatus;

/**
 * A refusal with the status the spec assigns it (spec/api.md §1.4). Services
 * throw it; its message is shown to the user, by the JSON API and the UI
 * alike.
 */
public class ServiceException extends RuntimeException {

	private final HttpStatus status;

	public ServiceException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

	/** 400: a required field is missing, or the request cannot be interpreted. */
	public static ServiceException badRequest(String message) {
		return new ServiceException(HttpStatus.BAD_REQUEST, message);
	}

	public static ServiceException unauthorized(String message) {
		return new ServiceException(HttpStatus.UNAUTHORIZED, message);
	}

	public static ServiceException notFound() {
		return new ServiceException(HttpStatus.NOT_FOUND, "not found");
	}

	/** 409: the record is in the wrong state for the operation. */
	public static ServiceException conflict(String message) {
		return new ServiceException(HttpStatus.CONFLICT, message);
	}

	/** 404 when an update matched no row. */
	public static void found(int rowsUpdated) {
		if (rowsUpdated == 0) {
			throw notFound();
		}
	}

	/** 422: a value is unacceptable, or a business rule refuses the request. */
	public static ServiceException unprocessable(String message) {
		return new ServiceException(HttpStatus.UNPROCESSABLE_ENTITY, message);
	}
}
