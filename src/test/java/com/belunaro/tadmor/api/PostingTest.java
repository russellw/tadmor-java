package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;

import com.belunaro.tadmor.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;

/**
 * Fixtures for tests that post to the journal: an open fiscal year of the
 * test's own (periods may never overlap, so each test gets a different
 * year), accounts and parties, and journal entries read as line multisets.
 */
abstract class PostingTest extends IntegrationTest {

	/** Each test posts into fiscal years of its own, far from every other test's. */
	private static final AtomicInteger years = new AtomicInteger(2600);

	protected String session;
	protected String adminSession;
	protected int y;

	@BeforeEach
	void setUp() throws Exception {
		session = login(createUser(false));
		adminSession = login(createUser(true));
		y = years.incrementAndGet();
		create("/api/fiscal-years", "{\"name\":\"FY-doc-" + y + "\",\"start_date\":\"" + y + "-01-01\",\"end_date\":\"" + y + "-12-31\"}");
	}

	protected int create(String path, String body) throws Exception {
		HttpResponse<String> r = postJson(path, body, session);
		assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(201);
		return json(r.body()).get("id").asInt();
	}

	protected JsonNode read(String path) throws Exception {
		HttpResponse<String> r = get(path, session);
		assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
		return json(r.body());
	}

	protected int account(String type) throws Exception {
		return create("/api/accounts", "{\"code\":\"D" + System.nanoTime() + "\",\"name\":\"X\",\"account_type\":\"" + type
				+ "\",\"is_postable\":true}");
	}

	protected int org() throws Exception {
		return create("/api/organizations", "{\"name\":\"Org " + System.nanoTime() + "\"}");
	}

	protected int customer(int ar) throws Exception {
		return create("/api/customers", "{\"organization_id\":" + org() + ",\"ar_account_id\":" + ar + "}");
	}

	protected int supplier(int ap) throws Exception {
		return create("/api/suppliers", "{\"organization_id\":" + org() + ",\"ap_account_id\":" + ap + "}");
	}

	protected static String uniq(String prefix) {
		return prefix + System.nanoTime();
	}

	protected String invoice(int customer, String date, String currency, String lines) {
		return "{\"invoice_number\":\"" + uniq("INV") + "\",\"customer_id\":" + customer + ",\"invoice_date\":\"" + date
				+ "\",\"currency_code\":\"" + currency + "\",\"lines\":[" + lines + "]}";
	}

	protected int post(String collection, int id) throws Exception {
		HttpResponse<String> r = postJson("/api/" + collection + "/" + id + "/post", "", session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		return json(r.body()).get("journal_entry_id").asInt();
	}

	protected static String dec(JsonNode n) {
		return new BigDecimal(n.asString()).stripTrailingZeros().toPlainString();
	}

	/** An entry's lines as "account dr|cr amount base", order ignored (not contract). */
	protected List<String> lines(int entry) throws Exception {
		List<String> out = new ArrayList<>();
		for (JsonNode l : read("/api/journal-entries/" + entry).get("lines")) {
			boolean debit = new BigDecimal(l.get("debit").asString()).signum() > 0;
			out.add(l.get("account_id").asInt() + (debit ? " dr " : " cr ") + dec(l.get(debit ? "debit" : "credit")) + " "
					+ dec(l.get(debit ? "base_debit" : "base_credit")));
		}
		return out;
	}

}
