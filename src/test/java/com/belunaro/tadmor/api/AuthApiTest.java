package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.belunaro.tadmor.IntegrationTest;

import org.junit.jupiter.api.Test;

/** spec/api.md §3, and the error conventions of §1.1 and §1.4 it relies on. */
class AuthApiTest extends IntegrationTest {

	private static String loginBody(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	@Test
	void loginReturnsUserAndSetsSessionCookie() throws Exception {
		TestUser user = createUser(true);
		HttpResponse<String> r = postJson("/api/auth/login", loginBody(user.email(), user.password()));
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(r.body()).contains("\"id\":" + user.id(), "\"email\":\"" + user.email() + "\"",
				"\"full_name\":\"Test User", "\"is_admin\":true").doesNotContain("password");

		String cookie = setCookie(r, "tadmor_session").orElseThrow();
		assertThat(cookie).contains("HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Secure");
		assertThat(r.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));

		// Only the token's SHA-256 is stored.
		String token = cookiePair(cookie).substring("tadmor_session=".length());
		byte[] stored = jdbc.sql("SELECT token_hash FROM sessions WHERE user_id = ?").param(user.id())
				.query(byte[].class).single();
		assertThat(stored).hasSize(32).isNotEqualTo(token.getBytes());
	}

	@Test
	void sessionCookieIsSecureBehindHttpsProxy() throws Exception {
		TestUser user = createUser(false);
		HttpResponse<String> r = send(request("/api/auth/login").header("Content-Type", "application/json")
				.header("X-Forwarded-Proto", "https")
				.POST(HttpRequest.BodyPublishers.ofString(loginBody(user.email(), user.password()))));
		assertThat(setCookie(r, "tadmor_session").orElseThrow()).contains("Secure");
	}

	@Test
	void emailIsCaseInsensitiveAndTrimmed() throws Exception {
		TestUser user = createUser(false);
		HttpResponse<String> r = postJson("/api/auth/login",
				loginBody("  " + user.email().toUpperCase() + " ", user.password()));
		assertThat(r.statusCode()).isEqualTo(200);
	}

	@Test
	void badCredentialsAreIndistinguishable401s() throws Exception {
		TestUser user = createUser(false);
		TestUser inactive = createUser(false);
		jdbc.sql("UPDATE users SET is_active = false WHERE id = ?").param(inactive.id()).update();

		HttpResponse<String> wrong = postJson("/api/auth/login", loginBody(user.email(), "not-the-password"));
		HttpResponse<String> unknown = postJson("/api/auth/login", loginBody("nobody@example.com", "whatever1"));
		HttpResponse<String> deactivated = postJson("/api/auth/login", loginBody(inactive.email(), inactive.password()));
		for (HttpResponse<String> r : java.util.List.of(wrong, unknown, deactivated)) {
			assertJsonError(r, 401);
			assertThat(r.body()).isEqualTo(wrong.body());
			assertThat(setCookie(r, "tadmor_session")).isEmpty();
		}
	}

	@Test
	void malformedLoginsAre400() throws Exception {
		assertJsonError(postJson("/api/auth/login", loginBody("", "password1")), 400);
		assertJsonError(postJson("/api/auth/login", loginBody("a@example.com", "")), 400);
		assertJsonError(postJson("/api/auth/login", "{\"email\":\"a@example.com\"}"), 400);
		assertJsonError(postJson("/api/auth/login", "{not json"), 400);
		assertJsonError(postJson("/api/auth/login", ""), 400);
	}

	@Test
	void unknownFieldsAreIgnored() throws Exception {
		TestUser user = createUser(false);
		HttpResponse<String> r = postJson("/api/auth/login",
				"{\"email\":\"" + user.email() + "\",\"password\":\"" + user.password() + "\",\"remember\":true}");
		assertThat(r.statusCode()).isEqualTo(200);
	}

	@Test
	void meNeedsASession() throws Exception {
		assertJsonError(get("/api/auth/me"), 401);

		TestUser user = createUser(false);
		HttpResponse<String> me = get("/api/auth/me", login(user));
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"id\":" + user.id(), "\"is_admin\":false");
	}

	@Test
	void logoutRevokesTheSessionAndIsIdempotent() throws Exception {
		String session = login(createUser(false));

		HttpResponse<String> out = postJson("/api/auth/logout", "", session);
		assertThat(out.statusCode()).isEqualTo(204);
		assertThat(setCookie(out, "tadmor_session").orElseThrow()).contains("Max-Age=0");

		HttpResponse<String> me = get("/api/auth/me", session);
		assertJsonError(me, 401);
		assertThat(setCookie(me, "tadmor_session").orElseThrow()).contains("Max-Age=0");

		assertThat(postJson("/api/auth/logout", "", session).statusCode()).isEqualTo(204);
		assertThat(postJson("/api/auth/logout", "").statusCode()).isEqualTo(204);
	}

	@Test
	void deactivationEndsSessionsAndDemotionIsImmediate() throws Exception {
		TestUser user = createUser(true);
		String session = login(user);

		jdbc.sql("UPDATE users SET is_admin = false WHERE id = ?").param(user.id()).update();
		assertThat(get("/api/auth/me", session).body()).contains("\"is_admin\":false");

		jdbc.sql("UPDATE users SET is_active = false WHERE id = ?").param(user.id()).update();
		assertJsonError(get("/api/auth/me", session), 401);
	}

	@Test
	void expiredSessionsAre401() throws Exception {
		TestUser user = createUser(false);
		String session = login(user);
		jdbc.sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = ?").param(user.id()).update();
		assertJsonError(get("/api/auth/me", session), 401);
	}

	@Test
	void sessionLastsThirtyDaysFromLogin() throws Exception {
		TestUser user = createUser(false);
		login(user);
		Integer days = jdbc.sql("SELECT round(extract(epoch FROM expires_at - created_at) / 86400)::int FROM sessions WHERE user_id = ?")
				.param(user.id()).query(Integer.class).single();
		assertThat(days).isEqualTo(30);
	}

	@Test
	void unknownPathsAndMethodsUnderApi() throws Exception {
		assertJsonError(get("/api/no-such-thing"), 401);

		String session = login(createUser(false));
		assertJsonError(get("/api/no-such-thing", session), 404);
		assertJsonError(get("/api/auth/login", session), 404);
	}
}
