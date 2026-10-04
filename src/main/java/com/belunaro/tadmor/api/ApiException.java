package com.belunaro.tadmor.api;

import org.springframework.http.HttpStatus;

/**
 * A refusal with the status the spec assigns it (spec/api.md §1.4). Its
 * message is shown to the user, by the JSON API and the UI alike.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	public ApiException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, message);
	}

	public static ApiException unauthorized(String message) {
		return new ApiException(HttpStatus.UNAUTHORIZED, message);
	}
}
