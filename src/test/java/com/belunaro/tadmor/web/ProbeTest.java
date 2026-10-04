package com.belunaro.tadmor.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.belunaro.tadmor.IntegrationTest;
import com.belunaro.tadmor.db.Migrations;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

class ProbeTest extends IntegrationTest {

	@Autowired
	Migrations migrations;

	@Test
	void healthzAndReadyz() throws Exception {
		HttpResponse<String> health = get("/healthz");
		assertThat(health.statusCode()).isEqualTo(200);
		assertThat(health.body()).isEqualTo("{\"status\":\"ok\"}");

		HttpResponse<String> ready = get("/readyz");
		assertThat(ready.statusCode()).isEqualTo(200);
		assertThat(ready.body()).isEqualTo("{\"status\":\"ready\"}");
	}

	@Test
	void migrationsAppliedOnceAtStartup() throws Exception {
		assertThat(jdbc.sql("SELECT count(*) FROM schema_migrations").query(Integer.class).single()).isPositive();
		assertThat(jdbc.sql("SELECT code FROM currencies WHERE code = 'EUR'").query(String.class).optional()).hasValue("EUR");
		assertThat(migrations.apply()).isEmpty();
	}
}
