package com.belunaro.tadmor.web;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The unauthenticated probes of spec/api.md §2. */
@RestController
public class ProbeController {

	private final JdbcClient jdbc;

	public ProbeController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/healthz")
	public Map<String, String> healthz() {
		return Map.of("status", "ok");
	}

	@GetMapping("/readyz")
	public ResponseEntity<Map<String, String>> readyz() {
		try {
			jdbc.sql("SELECT 1").query(Integer.class).single();
			return ResponseEntity.ok(Map.of("status", "ready"));
		} catch (DataAccessException e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "database unavailable"));
		}
	}
}
