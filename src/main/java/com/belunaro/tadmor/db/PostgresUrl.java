package com.belunaro.tadmor.db;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * A libpq connection URL ({@code postgres://user:pass@host:port/db?sslmode=...})
 * translated to pgjdbc's form. Query parameters pass through unchanged; the
 * ones tadmor uses (sslmode) mean the same to both drivers.
 */
public record PostgresUrl(String jdbcUrl, String user, String password) {

	public static PostgresUrl parse(String url) {
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("DATABASE_URL is not a valid URL", e);
		}
		if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
			throw new IllegalArgumentException("DATABASE_URL must start with postgres://");
		}
		if (uri.getHost() == null) {
			throw new IllegalArgumentException("DATABASE_URL has no host");
		}
		String user = null;
		String password = null;
		String userInfo = uri.getRawUserInfo();
		if (userInfo != null) {
			int colon = userInfo.indexOf(':');
			user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
			password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
		}
		StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
		if (uri.getPort() >= 0) {
			jdbc.append(':').append(uri.getPort());
		}
		jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
		if (uri.getRawQuery() != null) {
			jdbc.append('?').append(uri.getRawQuery());
		}
		return new PostgresUrl(jdbc.toString(), user, password);
	}

	private static String decode(String s) {
		return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	@Override
	public String toString() {
		return "PostgresUrl[" + jdbcUrl + ", user=" + user + "]";
	}
}
