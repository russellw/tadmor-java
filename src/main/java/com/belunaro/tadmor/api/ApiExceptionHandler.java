package com.belunaro.tadmor.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions from the API controllers into the spec's error body,
 * {@code {"error": "..."}} (spec/api.md §1.4). Errors raised outside any
 * controller (an unknown path, an unknown method) reach ErrorController.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler
	public ResponseEntity<Map<String, String>> refused(ApiException e) {
		return error(e.status(), e.getMessage());
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class })
	public ResponseEntity<Map<String, String>> unreadable(Exception e) {
		return error(HttpStatus.BAD_REQUEST, "request body must be a JSON object");
	}

	@ExceptionHandler
	public ResponseEntity<Map<String, String>> fault(Exception e) {
		log.error("request failed", e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
	}

	public static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message));
	}
}
