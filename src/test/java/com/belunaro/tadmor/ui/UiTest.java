package com.belunaro.tadmor.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.belunaro.tadmor.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;

/**
 * Drives the UI as a browser would: a signed-in session, and forms posted
 * with the CSRF token of the page they came from. Each test gets an open
 * fiscal year of its own, far from the API tests' years.
 */
abstract class UiTest extends IntegrationTest {

	private static final java.util.concurrent.atomic.AtomicInteger years = new java.util.concurrent.atomic.AtomicInteger(3000);
	private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

	/** A browser: its session cookie and the CSRF token and cookie of the last page it loaded. */
	protected final class Browser {
		final String session;
		String csrfField;
		String csrfCookie;
		HttpResponse<String> last;

		Browser(String session) {
			this.session = session;
		}

		private String cookies() {
			return csrfCookie == null ? session : session + "; " + csrfCookie;
		}

		/** GETs a page and remembers its CSRF token. */
		HttpResponse<String> get(String path) throws Exception {
			last = send(request(path).header("Cookie", cookies()));
			Matcher m = CSRF.matcher(last.body());
			if (m.find()) {
				csrfField = m.group(1);
			}
			setCookie(last, "XSRF-TOKEN").ifPresent(c -> csrfCookie = cookiePair(c));
			return last;
		}

		/** GETs a page that must render. */
		String page(String path) throws Exception {
			HttpResponse<String> r = get(path);
			assertThat(r.statusCode()).as(path + ": " + r.body()).isEqualTo(200);
			return r.body();
		}

		/** POSTs a form, as name, value pairs, with the CSRF token of the last page loaded. */
		HttpResponse<String> post(String path, String... pairs) throws Exception {
			if (csrfField == null) {
				get("/");
			}
			List<String> fields = new ArrayList<>(List.of("_csrf", csrfField));
			fields.addAll(List.of(pairs));
			StringBuilder body = new StringBuilder();
			for (int i = 0; i < fields.size(); i += 2) {
				body.append(i == 0 ? "" : "&").append(enc(fields.get(i))).append('=').append(enc(fields.get(i + 1)));
			}
			last = send(request(path).header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", cookies())
					.POST(HttpRequest.BodyPublishers.ofString(body.toString())));
			return last;
		}

		/** POSTs a form that must succeed with a redirect; returns where it redirects to. */
		String submit(String path, String... pairs) throws Exception {
			HttpResponse<String> r = post(path, pairs);
			assertThat(r.statusCode()).as(path + ": " + r.body()).isEqualTo(302);
			return r.headers().firstValue("Location").orElseThrow().replaceFirst("^https?://[^/]+", "");
		}
	}

	protected Browser user;
	protected Browser admin;
	protected int y;

	@BeforeEach
	void browsers() throws Exception {
		user = new Browser(login(createUser(false)));
		admin = new Browser(login(createUser(true)));
		y = years.incrementAndGet();
		user.submit("/fiscal-years/new", "name", "FY-ui-" + y, "start_date", y + "-01-01", "end_date", y + "-12-31");
	}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}

	/** The id at the end of a redirect such as /sales-invoices/12. */
	protected static int idOf(String location) {
		return Integer.parseInt(location.replaceAll(".*/(\\d+)$", "$1"));
	}

	/** A select's option value whose label contains the text. */
	protected static String option(String html, String name, String labelPart) {
		Matcher select = Pattern.compile("<select[^>]*name=\"" + Pattern.quote(name) + "\"[^>]*>(.*?)</select>", Pattern.DOTALL)
				.matcher(html);
		assertThat(select.find()).as("select " + name).isTrue();
		Matcher option = Pattern.compile("<option value=\"([^\"]*)\"[^>]*>([^<]*)</option>").matcher(select.group(1));
		while (option.find()) {
			if (option.group(2).contains(labelPart)) {
				return option.group(1);
			}
		}
		throw new AssertionError("no option containing " + labelPart + " in " + name);
	}

	protected static String uniq(String prefix) {
		return prefix + System.nanoTime();
	}
}
