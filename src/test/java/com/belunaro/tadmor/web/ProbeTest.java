package com.belunaro.tadmor.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.belunaro.tadmor.TestDatabase;
import com.belunaro.tadmor.db.Migrations;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProbeTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		TestDatabase.register(registry);
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Migrations migrations;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

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
	void apiRequiresSession() throws Exception {
		HttpResponse<String> me = get("/api/auth/me");
		assertThat(me.statusCode()).isEqualTo(401);
		assertThat(me.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
		assertThat(me.body()).contains("\"error\"");
	}

	@Test
	void migrationsAppliedOnceAtStartup() throws Exception {
		assertThat(jdbc.sql("SELECT count(*) FROM schema_migrations").query(Integer.class).single()).isPositive();
		assertThat(jdbc.sql("SELECT code FROM currencies WHERE code = 'EUR'").query(String.class).optional()).hasValue("EUR");
		assertThat(migrations.apply()).isEmpty();
	}
}
