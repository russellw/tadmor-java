package com.belunaro.tadmor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
		return sendJson("POST", path, body, cookies);
	}

	protected HttpResponse<String> sendJson(String method, String path, String body, String... cookies)
			throws IOException, InterruptedException {
		HttpRequest.Builder r = request(path).header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body));
		if (cookies.length > 0) {
			r.header("Cookie", String.join("; ", cookies));
		}
		return send(r);
	}

	/** Logs the user in through the API and returns the session cookie, ready to send. */
	protected String login(TestUser user) throws IOException, InterruptedException {
		HttpResponse<String> r = postJson("/api/auth/login",
				"{\"email\":\"" + user.email() + "\",\"password\":\"" + user.password() + "\"}");
		if (r.statusCode() != 200) {
			throw new AssertionError("login failed: " + r.statusCode() + " " + r.body());
		}
		return cookiePair(setCookie(r, "tadmor_session").orElseThrow());
	}

	/** Asserts the status and the spec's JSON error shape (spec/api.md §1.4). */
	protected static void assertJsonError(HttpResponse<String> r, int status) {
		assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
		assertThat(r.headers().firstValue("Content-Type"))
				.hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
		assertThat(r.body()).startsWith("{\"error\":\"");
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Parses a response body. */
	protected static JsonNode json(String body) {
		return JSON.readTree(body);
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
