package com.belunaro.tadmor.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Login sessions in the shared sessions table, as tadmor keeps them: a random
 * bearer token lives only in the client's cookie, and the table holds its
 * SHA-256. A session lasts a fixed 30 days from login, not sliding, and ends
 * early at logout, when its user is deactivated, or when an administrator
 * resets the user's password (spec/api.md §3).
 */
@Service
public class Sessions {

	public static final String COOKIE = "tadmor_session";
	public static final Duration TTL = Duration.ofDays(30);

	private static final SecureRandom random = new SecureRandom();

	private final JdbcClient jdbc;

	public Sessions(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Mints a session for the user and returns its token. Expired rows are
	 * pruned here, so no background job is needed.
	 */
	public String create(int userId) {
		byte[] raw = new byte[32];
		random.nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		jdbc.sql("DELETE FROM sessions WHERE expires_at < now()").update();
		jdbc.sql("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
				.params(hash(token), userId, Timestamp.from(Instant.now().plus(TTL)))
				.update();
		return token;
	}

	/** The user of a live session, if the token names one and its user is active. */
	public Optional<CurrentUser> user(String token) {
		return jdbc.sql("""
				SELECT u.id, u.email, u.full_name, u.is_admin
				FROM sessions s JOIN users u ON u.id = s.user_id
				WHERE s.token_hash = ? AND s.expires_at > now() AND u.is_active""")
				.param(hash(token))
				.query((rs, n) -> new CurrentUser(rs.getInt("id"), rs.getString("email"),
						rs.getString("full_name"), rs.getBoolean("is_admin")))
				.optional();
	}

	/** Revokes the session; an unknown token is not an error. */
	public void revoke(String token) {
		jdbc.sql("DELETE FROM sessions WHERE token_hash = ?").param(hash(token)).update();
	}

	/** Revokes every session of the user, as a password reset does. */
	public void revokeAll(int userId) {
		jdbc.sql("DELETE FROM sessions WHERE user_id = ?").param(userId).update();
	}

	public static Optional<String> token(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie c : cookies) {
			if (COOKIE.equals(c.getName()) && !c.getValue().isEmpty()) {
				return Optional.of(c.getValue());
			}
		}
		return Optional.empty();
	}

	public static void setCookie(HttpServletRequest request, HttpServletResponse response, String token) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, token, TTL).toString());
	}

	public static void clearCookie(HttpServletRequest request, HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
	}

	private static ResponseCookie cookie(HttpServletRequest request, String value, Duration maxAge) {
		return ResponseCookie.from(COOKIE, value)
				.path("/")
				.maxAge(maxAge)
				.httpOnly(true)
				.secure(isHttps(request))
				.sameSite("Lax")
				.build();
	}

	/** Whether the client connected over HTTPS, directly or through Caddy. */
	private static boolean isHttps(HttpServletRequest request) {
		return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
	}

	private static byte[] hash(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
