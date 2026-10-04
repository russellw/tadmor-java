package com.belunaro.tadmor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that drive the running server over HTTP. All subclasses
 * share one application context, so one freshly migrated test database;
 * tests create their own users with unique emails rather than assume an
 * empty table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

	private static final AtomicInteger users = new AtomicInteger();

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		TestDatabase.register(registry);
	}

	@LocalServerPort
	protected int port;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected PasswordEncoder encoder;

	/** Never follows redirects, so tests see them. */
	protected final HttpClient http = HttpClient.newHttpClient();

	public record TestUser(int id, String email, String password) {
	}

	protected TestUser createUser(boolean admin) {
		String email = "user" + users.incrementAndGet() + "-" + System.nanoTime() + "@example.com";
		String password = "password-" + users.get();
		int id = AddUser.upsert(jdbc, encoder, email, "Test User " + users.get(), password, admin);
		return new TestUser(id, email, password);
	}

	protected HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
	}

	protected HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	protected HttpResponse<String> get(String path, String... cookies) throws IOException, InterruptedException {
		HttpRequest.Builder r = request(path);
		if (cookies.length > 0) {
			r.header("Cookie", String.join("; ", cookies));
		}
		return send(r);
	}

	protected HttpResponse<String> postJson(String path, String body, String... cookies)
			throws IOException, InterruptedException {
		HttpRequest.Builder r = request(path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (cookies.length > 0) {
			r.header("Cookie", String.join("; ", cookies));
		}
		return send(r);
	}

	/** The Set-Cookie header for the named cookie, if the response set it. */
	protected static Optional<String> setCookie(HttpResponse<?> response, String name) {
		List<String> all = response.headers().allValues("Set-Cookie");
		return all.stream().filter(c -> c.startsWith(name + "=")).findFirst();
	}

	/** "name=value" from a Set-Cookie header, ready to send back. */
	protected static String cookiePair(String setCookie) {
		int semi = setCookie.indexOf(';');
		return semi < 0 ? setCookie : setCookie.substring(0, semi);
	}
}
