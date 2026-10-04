package com.belunaro.tadmor.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The spec's write responses (spec/api.md §1.3). */
final class Responses {

	private Responses() {
	}

	/** 201 with the new synthetic key, {@code {"id": n}}. */
	static ResponseEntity<Map<String, Object>> createdId(int id) {
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/** 201 with the new natural key as stored, {@code {"code": "..."}}. */
	static ResponseEntity<Map<String, Object>> createdCode(String code) {
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("code", code));
	}

	/** 204: nothing to report. */
	static ResponseEntity<Void> noContent() {
		return ResponseEntity.noContent().build();
	}
}
