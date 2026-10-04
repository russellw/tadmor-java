package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import com.belunaro.tadmor.IntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** spec/api.md §5.7 and §5.8, with spec/domain.md §9.1. */
class CalendarApiTest extends IntegrationTest {

	/** Each test gets years of its own, far from anything else, since periods may never overlap. */
	private static final AtomicInteger years = new AtomicInteger(2500);

	private String session;
	private String adminSession;
	private String savedSettings;

	@BeforeEach
	void signIn() throws Exception {
		session = login(createUser(false));
		adminSession = login(createUser(true));
		savedSettings = get("/api/settings", session).body();
	}

	@AfterEach
	void restoreSettings() throws Exception {
		sendJson("PUT", "/api/settings", savedSettings, adminSession);
	}

	private int create(String path, String body) throws Exception {
		HttpResponse<String> r = postJson(path, body, session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		return Integer.parseInt(r.body().replaceAll("\\D", ""));
	}

	private String read(String path) throws Exception {
		HttpResponse<String> r = get(path, session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		return r.body();
	}

	private static String year(String name, int y) {
		return "{\"name\":\"" + name + "\",\"start_date\":\"" + y + "-01-01\",\"end_date\":\"" + y + "-12-31\"}";
	}

	private static String period(int fy, String name, String start, String end) {
		return "{\"fiscal_year_id\":" + fy + ",\"name\":\"" + name + "\",\"start_date\":\"" + start
				+ "\",\"end_date\":\"" + end + "\"";
	}

	@Test
	void fiscalYearsAreCreatedOpenAndStatusIsNotEditable() throws Exception {
		int y = years.incrementAndGet();
		String name = "FY-test-" + y;
		int fy = create("/api/fiscal-years", year(name, y));
		assertThat(read("/api/fiscal-years/" + fy)).contains("\"status\":\"open\"", "\"start_date\":\"" + y + "-01-01\"");

		assertJsonError(postJson("/api/fiscal-years", year(name, y + 100), session), 409);
		assertJsonError(postJson("/api/fiscal-years", "{\"name\":\"X\",\"start_date\":\"\",\"end_date\":\"2999-12-31\"}", session), 400);
		assertJsonError(postJson("/api/fiscal-years", "{\"name\":\"Y" + y + "\",\"start_date\":\"" + y + "-12-31\",\"end_date\":\"" + y + "-01-01\"}", session), 422);
		assertJsonError(postJson("/api/fiscal-years", "{\"name\":\"Z" + y + "\",\"start_date\":\"2101-13-45\",\"end_date\":\"2101-12-31\"}", session), 422);
		assertJsonError(postJson("/api/fiscal-years", "{\"name\":\"W" + y + "\",\"start_date\":\"someday\",\"end_date\":\"2101-12-31\"}", session), 422);

		String renamed = year(name + "-renamed", y).replace("}", ",\"status\":\"closed\"}");
		assertThat(sendJson("PUT", "/api/fiscal-years/" + fy, renamed, session).statusCode()).isEqualTo(204);
		assertThat(read("/api/fiscal-years/" + fy)).contains("-renamed", "\"status\":\"open\"");
		assertJsonError(sendJson("PUT", "/api/fiscal-years/999999", year("X", 2999), session), 404);
	}

	@Test
	void periodsNeverOverlapAndCloseByEditing() throws Exception {
		int y = years.incrementAndGet();
		int fy = create("/api/fiscal-years", year("FY-p-" + y, y));
		int q1 = create("/api/accounting-periods", period(fy, "Q1", y + "-01-01", y + "-03-31") + "}");
		assertThat(read("/api/accounting-periods/" + q1)).contains("\"status\":\"open\"", "\"fiscal_year_id\":" + fy);

		assertJsonError(postJson("/api/accounting-periods", "{\"name\":\"Q1\",\"start_date\":\"2101-01-01\",\"end_date\":\"2101-03-31\"}", session), 400);
		assertJsonError(postJson("/api/accounting-periods", period(fy, "Q1", y + "-04-01", y + "-06-30") + "}", session), 409);
		assertJsonError(postJson("/api/accounting-periods", period(fy, "Overlap", y + "-03-31", y + "-04-30") + "}", session), 422);
		assertJsonError(postJson("/api/accounting-periods", period(999999, "X", y + "-07-01", y + "-07-31") + "}", session), 422);

		// Not even across fiscal years.
		int other = create("/api/fiscal-years", year("FY-o-" + y, y));
		assertJsonError(postJson("/api/accounting-periods", period(other, "Feb", y + "-02-01", y + "-02-28") + "}", session), 422);

		String q1Body = period(fy, "Q1", y + "-01-01", y + "-03-31");
		assertThat(sendJson("PUT", "/api/accounting-periods/" + q1, q1Body + ",\"status\":\"closed\"}", session).statusCode()).isEqualTo(204);
		assertThat(read("/api/accounting-periods/" + q1)).contains("\"status\":\"closed\"");
		assertThat(sendJson("PUT", "/api/accounting-periods/" + q1, q1Body + "}", session).statusCode()).isEqualTo(204);
		assertThat(read("/api/accounting-periods/" + q1)).contains("\"status\":\"open\"");
		assertJsonError(sendJson("PUT", "/api/accounting-periods/999999", q1Body + "}", session), 404);
	}

	@Test
	void aClosedYearHasNoOpenPeriods() throws Exception {
		int y = years.incrementAndGet();
		int fy = create("/api/fiscal-years", year("FY-c-" + y, y));
		int jan = create("/api/accounting-periods", period(fy, "Jan", y + "-01-01", y + "-01-31") + "}");
		jdbc.sql("UPDATE accounting_periods SET status = 'closed' WHERE fiscal_year_id = ?").param(fy).update();
		jdbc.sql("UPDATE fiscal_years SET status = 'closed' WHERE id = ?").param(fy).update();

		assertJsonError(postJson("/api/accounting-periods", period(fy, "Feb", y + "-02-01", y + "-02-28") + "}", session), 422);
		assertJsonError(sendJson("PUT", "/api/accounting-periods/" + jan, period(fy, "Jan", y + "-01-01", y + "-01-31") + "}", session), 422);
	}

	@Test
	void settingsAreForAdministratorsToChange() throws Exception {
		String settings = read("/api/settings");
		assertThat(settings).contains("\"base_currency\":", "\"fx_gain_loss_account_id\":");
		assertJsonError(sendJson("PUT", "/api/settings", settings, session), 403);

		int summary = create("/api/accounts", "{\"code\":\"S" + System.nanoTime() + "\",\"name\":\"Header\",\"account_type\":\"expense\"}");
		assertJsonError(sendJson("PUT", "/api/settings", "{\"base_currency\":\"\"}", adminSession), 400);
		assertJsonError(sendJson("PUT", "/api/settings", "{\"base_currency\":\"US\"}", adminSession), 422);
		assertJsonError(sendJson("PUT", "/api/settings", "{\"base_currency\":\"ZZZ\"}", adminSession), 422);
		assertJsonError(sendJson("PUT", "/api/settings", "{\"base_currency\":\"USD\",\"fx_gain_loss_account_id\":" + summary + "}", adminSession), 422);

		// Keeps the base currency: other tests may already have posted entries, which freeze it.
		String base = json(settings).get("base_currency").asString();
		assertThat(sendJson("PUT", "/api/settings", "{\"base_currency\":\"" + base.toLowerCase() + "\",\"fx_gain_loss_account_id\":null}",
				adminSession).statusCode()).isEqualTo(204);
		assertThat(read("/api/settings")).isEqualTo("{\"base_currency\":\"" + base + "\",\"fx_gain_loss_account_id\":null}");
	}

	@Test
	void exchangeRates() throws Exception {
		String rate = "{\"currency_code\":\"cad\",\"rate_date\":\"1902-01-01\",\"rate\":\"1.30\"}";
		HttpResponse<String> r = postJson("/api/exchange-rates", rate, session);
		assertThat(r.statusCode()).isEqualTo(201);
		assertThat(r.body()).contains("\"currency_code\":\"CAD\"", "\"rate_date\":\"1902-01-01\"");
		assertJsonError(postJson("/api/exchange-rates", rate, session), 409);
		assertThat(postJson("/api/exchange-rates", "{\"currency_code\":\"CAD\",\"rate_date\":\"1902-01-02\",\"rate\":\"1.31\"}", session).statusCode())
				.isEqualTo(201);

		assertJsonError(postJson("/api/exchange-rates", "{\"currency_code\":\"CAD\",\"rate_date\":\"\",\"rate\":\"1.3\"}", session), 400);
		assertJsonError(postJson("/api/exchange-rates", "{\"currency_code\":\"ZZZ\",\"rate_date\":\"1902-01-01\",\"rate\":\"1.3\"}", session), 422);
		assertJsonError(postJson("/api/exchange-rates", "{\"currency_code\":\"CAD\",\"rate_date\":\"1902-02-01\",\"rate\":\"0\"}", session), 422);

		String list = read("/api/exchange-rates");
		assertThat(list.indexOf("1902-01-02")).isLessThan(list.indexOf("1902-01-01"));
		assertThat(list).contains("\"rate\":\"1.3\"");

		assertThat(sendJson("PUT", "/api/exchange-rates/cad/1902-01-01", "{\"rate\":\"1.325\"}", session).statusCode()).isEqualTo(204);
		assertThat(read("/api/exchange-rates")).contains("\"rate_date\":\"1902-01-01\",\"rate\":\"1.325\"");
		assertJsonError(sendJson("PUT", "/api/exchange-rates/CAD/1902-03-01", "{\"rate\":\"1.5\"}", session), 404);

		assertThat(sendJson("DELETE", "/api/exchange-rates/CAD/1902-01-01", "", session).statusCode()).isEqualTo(204);
		assertThat(sendJson("DELETE", "/api/exchange-rates/CAD/1902-01-02", "", session).statusCode()).isEqualTo(204);
		assertJsonError(sendJson("DELETE", "/api/exchange-rates/CAD/1902-01-01", "", session), 404);
	}

	@Test
	void administratorOnlyRoutesAreRefusedBeforeAnythingElse() throws Exception {
		assertJsonError(postJson("/api/fiscal-years/1/close", "{\"retained_earnings_account_id\":1}", session), 403);
		assertJsonError(postJson("/api/fiscal-years/1/reopen", "", session), 403);
		assertJsonError(postJson("/api/sales-invoices/1/unpost", "", session), 403);
		assertJsonError(postJson("/api/stock-movements/1/unpost", "", session), 403);
		assertJsonError(postJson("/api/bank-statements/1/reopen", "", session), 403);
	}
}
