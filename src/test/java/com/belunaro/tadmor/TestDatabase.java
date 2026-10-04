package com.belunaro.tadmor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.belunaro.tadmor.db.PostgresUrl;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The database integration tests run against: TEST_DATABASE_URL, whose
 * public schema is dropped and recreated before each application context
 * starts (and so migrates it from scratch). Point it only at a throwaway
 * database.
 */
public final class TestDatabase {

	private TestDatabase() {
	}

	public static String url() {
		String url = System.getenv("TEST_DATABASE_URL");
		if (url == null || url.isBlank()) {
			throw new IllegalStateException("TEST_DATABASE_URL is required to run the tests");
		}
		return url;
	}

	/** Wipes the test database and points the application context at it. */
	public static void register(DynamicPropertyRegistry registry) {
		String url = url();
		reset(url);
		registry.add("tadmor.database-url", () -> url);
	}

	public static void reset(String url) {
		PostgresUrl pg = PostgresUrl.parse(url);
		try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.user(), pg.password());
				Statement s = c.createStatement()) {
			s.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
		} catch (SQLException e) {
			throw new IllegalStateException("cannot reset the test database", e);
		}
	}
}
