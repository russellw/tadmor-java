package com.belunaro.tadmor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;

class DatabaseErrorsTest {

	private static UncategorizedSQLException failure(String state, String message) {
		return new UncategorizedSQLException("task", "sql", new SQLException(message, state));
	}

	@Test
	void mapsSqlStatesAsTadmorDoes() {
		assertThat(DatabaseErrors.refusal(failure("23505", "x")).orElseThrow().status()).isEqualTo(HttpStatus.CONFLICT);
		for (String state : new String[] { "23503", "23514", "23502", "23P01", "P0001", "22P02", "22003" }) {
			assertThat(DatabaseErrors.refusal(failure(state, "x")).orElseThrow().status()).as(state)
					.isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
		}
		assertThat(DatabaseErrors.refusal(failure("42601", "syntax error"))).isEmpty();
		assertThat(DatabaseErrors.refusal(failure("08006", "connection failure"))).isEmpty();
	}

	@Test
	void keepsOnlyTheServerMessage() {
		var e = failure("P0001", "ERROR: cannot post: no open period\n  Where: PL/pgSQL function post()");
		assertThat(DatabaseErrors.refusal(e).orElseThrow().getMessage()).isEqualTo("cannot post: no open period");
	}
}
