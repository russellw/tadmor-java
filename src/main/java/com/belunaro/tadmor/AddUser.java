package com.belunaro.tadmor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The out-of-band bootstrap of a login (spec/api.md §3), as tadmor's
 * {@code server -adduser}: creates the user, or when the email exists resets
 * its name, password, and admin flag and reactivates it. The password is the
 * first line of stdin. The user is an administrator unless --admin=false.
 * Pending migrations are applied first, so it works on a fresh database.
 *
 * <pre>
 * echo 'the-password' | java -jar tadmor.jar adduser --email=you@example.com --name='Your Name'
 * </pre>
 */
public final class AddUser {

	private AddUser() {
	}

	static void main(String[] args) throws IOException {
		String email = null;
		String name = null;
		boolean admin = true;
		for (String arg : args) {
			if (arg.startsWith("--email=")) {
				email = arg.substring("--email=".length()).strip();
			} else if (arg.startsWith("--name=")) {
				name = arg.substring("--name=".length()).strip();
			} else if (arg.equals("--admin=false")) {
				admin = false;
			} else if (!arg.equals("--admin=true")) {
				fail("unknown argument " + arg + "\nusage: adduser --email=EMAIL --name=NAME [--admin=false] < password");
			}
		}
		if (email == null || email.isEmpty() || name == null || name.isEmpty()) {
			fail("adduser requires --email and --name");
		}
		if (!email.contains("@")) {
			fail("email must contain @");
		}
		String password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
		if (password == null || password.length() < 8) {
			fail("password (the first line of stdin) must be at least 8 characters");
		}

		SpringApplication app = new SpringApplication(TadmorApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		app.setLogStartupInfo(false);
		try (ConfigurableApplicationContext context = app.run()) {
			int id = upsert(context.getBean(JdbcClient.class), context.getBean(PasswordEncoder.class),
					email, name, password, admin);
			System.out.println("user " + id + " " + email + (admin ? " (administrator)" : ""));
		}
	}

	/** Creates or resets the user and returns its id. */
	public static int upsert(JdbcClient jdbc, PasswordEncoder encoder, String email, String fullName, String password,
			boolean isAdmin) {
		return jdbc.sql("""
				INSERT INTO users (email, full_name, password_hash, is_admin) VALUES (?, ?, ?, ?)
				ON CONFLICT (email) DO UPDATE
				SET full_name = EXCLUDED.full_name, password_hash = EXCLUDED.password_hash,
				    is_active = true, is_admin = EXCLUDED.is_admin
				RETURNING id""")
				.params(email, fullName, encoder.encode(password), isAdmin)
				.query(Integer.class)
				.single();
	}

	private static void fail(String message) {
		System.err.println(message);
		System.exit(2);
	}
}
