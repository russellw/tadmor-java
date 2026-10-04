package com.belunaro.tadmor.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.belunaro.tadmor.IntegrationTest;

import org.junit.jupiter.api.Test;

/** spec/domain.md §13 G1, G2, and G8. */
class LoginUiTest extends IntegrationTest {

	private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	/** The login form's CSRF field and the cookie that backs it. */
	private record Csrf(String field, String cookie) {
	}

	private Csrf csrf() throws Exception {
		HttpResponse<String> page = get("/login");
		assertThat(page.statusCode()).isEqualTo(200);
		Matcher m = CSRF_FIELD.matcher(page.body());
		assertThat(m.find()).as("login form has a CSRF field").isTrue();
		return new Csrf(m.group(1), cookiePair(setCookie(page, "XSRF-TOKEN").orElseThrow()));
	}

	private HttpResponse<String> postForm(String path, String form, String... cookies) throws Exception {
		return send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
				.header("Cookie", String.join("; ", cookies))
				.POST(HttpRequest.BodyPublishers.ofString(form)));
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private HttpResponse<String> submitLogin(Csrf csrf, String email, String password) throws Exception {
		return postForm("/login", "_csrf=" + enc(csrf.field()) + "&email=" + enc(email) + "&password=" + enc(password),
				csrf.cookie());
	}

	@Test
	void pagesRedirectToLoginWithoutSession() throws Exception {
		HttpResponse<String> home = get("/");
		assertThat(home.statusCode()).isEqualTo(302);
		assertThat(home.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/login"));
		assertThat(get("/no-such-page").statusCode()).isEqualTo(302);
	}

	@Test
	void signInShowsNameAndSignOutEndsSession() throws Exception {
		TestUser user = createUser(false);
		Csrf csrf = csrf();
		HttpResponse<String> in = submitLogin(csrf, user.email(), user.password());
		assertThat(in.statusCode()).isEqualTo(302);
		assertThat(in.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/"));
		String session = cookiePair(setCookie(in, "tadmor_session").orElseThrow());
		assertThat(in.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));

		HttpResponse<String> home = get("/", session);
		assertThat(home.statusCode()).isEqualTo(200);
		assertThat(home.body()).contains(jdbc.sql("SELECT full_name FROM users WHERE id = ?").param(user.id())
				.query(String.class).single(), "Sign out");

		// Signed in, the login page sends you home.
		assertThat(get("/login", session).statusCode()).isEqualTo(302);

		HttpResponse<String> out = postForm("/logout", "_csrf=" + enc(csrf.field()), session, csrf.cookie());
		assertThat(out.statusCode()).isEqualTo(302);
		assertThat(out.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/login"));
		assertThat(get("/", session).statusCode()).isEqualTo(302);
		assertThat(get("/api/auth/me", session).statusCode()).isEqualTo(401);
	}

	@Test
	void failedLoginShowsError() throws Exception {
		TestUser user = createUser(false);
		HttpResponse<String> r = submitLogin(csrf(), user.email(), "wrong-password");
		assertThat(r.statusCode()).isEqualTo(401);
		assertThat(r.body()).contains("Invalid email or password.", "value=\"" + user.email() + "\"");
		assertThat(setCookie(r, "tadmor_session")).isEmpty();
	}

	@Test
	void formsNeedCsrfToken() throws Exception {
		TestUser user = createUser(false);
		HttpResponse<String> r = postForm("/login", "email=" + enc(user.email()) + "&password=" + enc(user.password()));
		assertThat(r.statusCode()).isEqualTo(403);
		assertThat(setCookie(r, "tadmor_session")).isEmpty();
	}

	@Test
	void unknownAddressShowsNotFound() throws Exception {
		TestUser user = createUser(false);
		HttpResponse<String> in = submitLogin(csrf(), user.email(), user.password());
		String session = cookiePair(setCookie(in, "tadmor_session").orElseThrow());

		HttpResponse<String> r = get("/no-such-page", session);
		assertThat(r.statusCode()).isEqualTo(404);
		assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("text/html"));
		assertThat(r.body()).contains("Not found");
	}
}
