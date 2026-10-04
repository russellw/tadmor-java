package com.belunaro.tadmor.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PostgresUrlTest {

	@Test
	void translatesLibpqUrl() {
		PostgresUrl url = PostgresUrl.parse("postgres://tadmor:s%40cret+1@127.0.0.1:5432/tadmor?sslmode=disable");
		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/tadmor?sslmode=disable");
		assertThat(url.user()).isEqualTo("tadmor");
		assertThat(url.password()).isEqualTo("s@cret+1");
	}

	@Test
	void userAndPortAreOptional() {
		PostgresUrl url = PostgresUrl.parse("postgresql://db.internal/tadmor");
		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal/tadmor");
		assertThat(url.user()).isNull();
		assertThat(url.password()).isNull();
	}

	@Test
	void rejectsOtherSchemes() {
		assertThatThrownBy(() -> PostgresUrl.parse("mysql://localhost/tadmor")).isInstanceOf(IllegalArgumentException.class);
	}
}
