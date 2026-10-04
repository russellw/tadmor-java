package com.belunaro.tadmor;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.belunaro.tadmor.db.PostgresUrl;

/**
 * Drops and recreates the public schema of DATABASE_URL, for the conformance
 * run, which needs a fresh instance. Refuses any database whose name does not
 * end in _test or _conformance, so it cannot wipe real data by mistake.
 *
 * <pre>
 * DATABASE_URL=postgres://.../tadmor_java_conformance java -jar tadmor.jar resetdb
 * </pre>
 */
public final class ResetDb {

	private ResetDb() {
	}

	static void main(String[] args) throws SQLException {
		String url = System.getenv("DATABASE_URL");
		if (url == null || url.isBlank()) {
			fail("DATABASE_URL is required");
		}
		String name = URI.create(url).getPath().replaceFirst("^/", "");
		if (!name.endsWith("_test") && !name.endsWith("_conformance")) {
			fail("refusing to wipe database " + name + ": its name must end in _test or _conformance");
		}
		PostgresUrl pg = PostgresUrl.parse(url);
		try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.user(), pg.password());
				Statement s = c.createStatement()) {
			s.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
		}
		System.out.println("wiped " + name);
	}

	private static void fail(String message) {
		System.err.println(message);
		System.exit(2);
	}
}
