package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

import com.belunaro.tadmor.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** spec/api.md §5.1, and the administrator gating of spec/domain.md §12. */
class UsersApiTest extends IntegrationTest {

	private TestUser admin;
	private String session;

	@BeforeEach
	void signInAsAdmin() throws Exception {
		admin = createUser(true);
		session = login(admin);
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + System.nanoTime() + "@example.com";
	}

	private int create(String email, String password, boolean isAdmin) throws Exception {
		HttpResponse<String> r = postJson("/api/users", "{\"email\":\"" + email + "\",\"full_name\":\"Ann Example\",\"password\":\""
				+ password + "\",\"is_admin\":" + isAdmin + "}", session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		assertThat(r.body()).matches("\\{\"id\":\\d+\\}");
		return Integer.parseInt(r.body().replaceAll("\\D", ""));
	}

	private HttpResponse<String> put(int id, String body) throws Exception {
		return sendJson("PUT", "/api/users/" + id, body, session);
	}

	@Test
	void createGetAndList() throws Exception {
		String email = uniqueEmail("ann");
		int id = create("  " + email + " ", "longenough", false);

		HttpResponse<String> got = get("/api/users/" + id, session);
		assertThat(got.statusCode()).isEqualTo(200);
		assertThat(got.body()).contains("\"id\":" + id, "\"email\":\"" + email + "\"", "\"full_name\":\"Ann Example\"",
				"\"is_active\":true", "\"is_admin\":false").doesNotContain("password");

		HttpResponse<String> list = get("/api/users", session);
		assertThat(list.statusCode()).isEqualTo(200);
		assertThat(list.body()).startsWith("[").contains("\"id\":" + id).doesNotContain("password");
		List<String> emails = jdbc.sql("SELECT email FROM users ORDER BY email").query(String.class).list();
		int last = -1;
		for (String e : emails) {
			int at = list.body().indexOf("\"email\":\"" + e + "\"");
			assertThat(at).as("listed in email order: " + e).isGreaterThan(last);
			last = at;
		}
	}

	@Test
	void createRules() throws Exception {
		String email = uniqueEmail("rules");
		assertJsonError(postJson("/api/users", "{\"email\":\"\",\"full_name\":\"X\",\"password\":\"longenough\"}", session), 400);
		assertJsonError(postJson("/api/users", "{\"email\":\"" + email + "\",\"full_name\":\" \",\"password\":\"longenough\"}", session), 400);
		assertJsonError(postJson("/api/users", "{\"email\":\"" + email + "\",\"full_name\":\"X\"}", session), 400);
		assertJsonError(postJson("/api/users", "{\"email\":\"no-at-sign\",\"full_name\":\"X\",\"password\":\"longenough\"}", session), 422);
		assertJsonError(postJson("/api/users", "{\"email\":\"" + email + "\",\"full_name\":\"X\",\"password\":\"short\"}", session), 422);
		assertJsonError(postJson("/api/users", "[1,2,3]", session), 400);

		create(email, "longenough", false);
		assertJsonError(postJson("/api/users", "{\"email\":\"" + email.toUpperCase() + "\",\"full_name\":\"Dup\",\"password\":\"longenough\"}", session), 409);
	}

	@Test
	void newUsersStartActiveAndCanSignIn() throws Exception {
		String email = uniqueEmail("start");
		HttpResponse<String> r = postJson("/api/users", "{\"email\":\"" + email
				+ "\",\"full_name\":\"X\",\"password\":\"longenough\",\"is_active\":false}", session);
		assertThat(r.statusCode()).isEqualTo(201);
		int id = Integer.parseInt(r.body().replaceAll("\\D", ""));
		assertThat(get("/api/users/" + id, session).body()).contains("\"is_active\":true");
		assertThat(login(new TestUser(id, email, "longenough"))).startsWith("tadmor_session=");
	}

	@Test
	void updateIsAFullReplacement() throws Exception {
		String email = uniqueEmail("upd");
		int id = create(email, "longenough", true);

		HttpResponse<String> r = put(id, "{\"email\":\"" + email + "\",\"full_name\":\"Ann Renamed\"}");
		assertThat(r.statusCode()).isEqualTo(204);
		assertThat(get("/api/users/" + id, session).body())
				.contains("\"full_name\":\"Ann Renamed\"", "\"is_active\":false", "\"is_admin\":false");
	}

	@Test
	void updateRules() throws Exception {
		String email = uniqueEmail("rules-upd");
		int id = create(email, "longenough", false);
		String other = uniqueEmail("other");
		create(other, "longenough", false);

		assertJsonError(put(id, "{\"email\":\"" + email + "\",\"full_name\":\"\",\"is_active\":true}"), 400);
		assertJsonError(put(id, "{\"email\":\"nope\",\"full_name\":\"X\",\"is_active\":true}"), 422);
		assertJsonError(put(999999, "{\"email\":\"x@y.z\",\"full_name\":\"X\",\"is_active\":true}"), 404);
		assertJsonError(put(id, "{\"email\":\"" + other.toUpperCase() + "\",\"full_name\":\"X\",\"is_active\":true}"), 409);
	}

	@Test
	void administratorsCannotLockThemselvesOut() throws Exception {
		String me = "{\"email\":\"" + admin.email() + "\",\"full_name\":\"Me\",";
		assertJsonError(put(admin.id(), me + "\"is_active\":false,\"is_admin\":true}"), 422);
		assertJsonError(put(admin.id(), me + "\"is_active\":true,\"is_admin\":false}"), 422);
		assertThat(get("/api/users/" + admin.id(), session).body()).contains("\"is_active\":true", "\"is_admin\":true");
		assertThat(put(admin.id(), me + "\"is_active\":true,\"is_admin\":true}").statusCode()).isEqualTo(204);
	}

	@Test
	void passwordResetRevokesSessions() throws Exception {
		String email = uniqueEmail("reset");
		int id = create(email, "first-password", false);
		String theirs = login(new TestUser(id, email, "first-password"));

		assertJsonError(postJson("/api/users/" + id + "/password", "{\"password\":\"short\"}", session), 422);
		assertJsonError(postJson("/api/users/" + id + "/password", "{}", session), 400);
		assertJsonError(postJson("/api/users/999999/password", "{\"password\":\"longenough\"}", session), 404);
		assertThat(get("/api/auth/me", theirs).statusCode()).isEqualTo(200);

		assertThat(postJson("/api/users/" + id + "/password", "{\"password\":\"second-password\"}", session).statusCode())
				.isEqualTo(204);
		assertJsonError(get("/api/auth/me", theirs), 401);
		assertJsonError(postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"first-password\"}"), 401);
		assertThat(login(new TestUser(id, email, "second-password"))).startsWith("tadmor_session=");
	}

	@Test
	void administratorsOnly() throws Exception {
		TestUser ordinary = createUser(false);
		String theirs = login(ordinary);
		assertJsonError(get("/api/users", theirs), 403);
		assertJsonError(get("/api/users/" + ordinary.id(), theirs), 403);
		assertJsonError(postJson("/api/users", "{\"email\":\"x@y.z\",\"full_name\":\"X\",\"password\":\"longenough\"}", theirs), 403);
		assertJsonError(postJson("/api/users/" + ordinary.id() + "/password", "{\"password\":\"longenough\"}", theirs), 403);
		assertJsonError(get("/api/users"), 401);

		// Promotion takes effect on the existing session.
		jdbc.sql("UPDATE users SET is_admin = true WHERE id = ?").param(ordinary.id()).update();
		assertThat(get("/api/users", theirs).statusCode()).isEqualTo(200);
	}

	@Test
	void malformedIdsAre400() throws Exception {
		for (String id : List.of("abc", "0", "-3", "1.5", "9999999999999999999999")) {
			assertJsonError(get("/api/users/" + id, session), 400);
		}
		assertJsonError(get("/api/users/999999", session), 404);
	}
}
