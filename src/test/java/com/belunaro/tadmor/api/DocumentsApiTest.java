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
import org.junit.jupiter.api.Test;

/** spec/api.md §5.9 and §5.14, with spec/domain.md §2, §4, §7, and §9.2. */
class DocumentsApiTest extends IntegrationTest {

	/** Each test posts into fiscal years of its own, far from every other test's. */
	private static final AtomicInteger years = new AtomicInteger(2600);

	private String session;
	private String adminSession;
	private int y;

	@BeforeEach
	void setUp() throws Exception {
		session = login(createUser(false));
		adminSession = login(createUser(true));
		y = years.incrementAndGet();
		create("/api/fiscal-years", "{\"name\":\"FY-doc-" + y + "\",\"start_date\":\"" + y + "-01-01\",\"end_date\":\"" + y + "-12-31\"}");
	}

	private int create(String path, String body) throws Exception {
		HttpResponse<String> r = postJson(path, body, session);
		assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(201);
		return json(r.body()).get("id").asInt();
	}

	private JsonNode read(String path) throws Exception {
		HttpResponse<String> r = get(path, session);
		assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
		return json(r.body());
	}

	private int account(String type) throws Exception {
		return create("/api/accounts", "{\"code\":\"D" + System.nanoTime() + "\",\"name\":\"X\",\"account_type\":\"" + type
				+ "\",\"is_postable\":true}");
	}

	private int org() throws Exception {
		return create("/api/organizations", "{\"name\":\"Org " + System.nanoTime() + "\"}");
	}

	private int customer(int ar) throws Exception {
		return create("/api/customers", "{\"organization_id\":" + org() + ",\"ar_account_id\":" + ar + "}");
	}

	private int supplier(int ap) throws Exception {
		return create("/api/suppliers", "{\"organization_id\":" + org() + ",\"ap_account_id\":" + ap + "}");
	}

	private static String uniq(String prefix) {
		return prefix + System.nanoTime();
	}

	private String invoice(int customer, String date, String currency, String lines) {
		return "{\"invoice_number\":\"" + uniq("INV") + "\",\"customer_id\":" + customer + ",\"invoice_date\":\"" + date
				+ "\",\"currency_code\":\"" + currency + "\",\"lines\":[" + lines + "]}";
	}

	private int post(String collection, int id) throws Exception {
		HttpResponse<String> r = postJson("/api/" + collection + "/" + id + "/post", "", session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		return json(r.body()).get("journal_entry_id").asInt();
	}

	private static String dec(JsonNode n) {
		return new BigDecimal(n.asString()).stripTrailingZeros().toPlainString();
	}

	/** An entry's lines as "account dr|cr amount base", order ignored (not contract). */
	private List<String> lines(int entry) throws Exception {
		List<String> out = new ArrayList<>();
		for (JsonNode l : read("/api/journal-entries/" + entry).get("lines")) {
			boolean debit = new BigDecimal(l.get("debit").asString()).signum() > 0;
			out.add(l.get("account_id").asInt() + (debit ? " dr " : " cr ") + dec(l.get(debit ? "debit" : "credit")) + " "
					+ dec(l.get(debit ? "base_debit" : "base_credit")));
		}
		return out;
	}

	@Test
	void invoiceMoneyEditsAndListOrder() throws Exception {
		int income = account("revenue");
		int customer = customer(account("asset"));
		String number = uniq("INV");
		String body = "{\"invoice_number\":\"" + number + "\",\"customer_id\":" + customer + ",\"invoice_date\":\"" + y
				+ "-03-15\",\"due_date\":\"" + y + "-04-14\",\"currency_code\":\"USD\",\"reference\":\"PO-77\",\"memo\":\"Thanks\",\"lines\":["
				+ "{\"description\":\"Consulting\",\"quantity\":\"2\",\"unit_price\":\"10.005\",\"revenue_account_id\":" + income + "},"
				+ "{\"description\":\"Licence\",\"quantity\":\"3\",\"unit_price\":\"7.333\",\"revenue_account_id\":" + income
				+ ",\"tax_code\":\"STD\",\"tax_rate\":\"8.25\"}]}";
		int id = create("/api/sales-invoices", body);
		assertJsonError(postJson("/api/sales-invoices", body, session), 409);

		JsonNode inv = read("/api/sales-invoices/" + id);
		assertThat(inv.get("status").asString()).isEqualTo("draft");
		assertThat(inv.get("payment_status").asString()).isEqualTo("unpaid");
		assertThat(dec(inv.get("total"))).isEqualTo("43.8239");
		assertThat(dec(inv.get("balance"))).isEqualTo("43.8239");
		assertThat(inv.get("journal_entry_id").isNull()).isTrue();
		assertThat(inv.get("due_date").asString()).isEqualTo(y + "-04-14");

		JsonNode lines = read("/api/sales-invoices/" + id + "/lines");
		assertThat(lines).hasSize(2);
		assertThat(dec(lines.get(0).get("line_subtotal"))).isEqualTo("20.01");
		assertThat(dec(lines.get(1).get("tax_amount"))).isEqualTo("1.8149");
		assertThat(dec(lines.get(1).get("line_total"))).isEqualTo("23.8139");
		assertThat(lines.get(1).get("order_line_id").isNull()).isTrue();

		String replaced = body.replace("\"memo\":\"Thanks\"", "\"memo\":null")
				.replaceAll("\\[\\{.*\\}\\]", "[{\"description\":\"Flat\",\"unit_price\":\"210\",\"revenue_account_id\":" + income + "}]");
		assertThat(sendJson("PUT", "/api/sales-invoices/" + id, replaced, session).statusCode()).isEqualTo(204);
		inv = read("/api/sales-invoices/" + id);
		assertThat(dec(inv.get("total"))).isEqualTo("210");
		assertThat(inv.get("memo").isNull()).isTrue();
		assertThat(read("/api/sales-invoices/" + id + "/lines")).hasSize(1);

		int later = create("/api/sales-invoices", invoice(customer, y + "-06-01", "USD", "{\"description\":\"x\"}"));
		int earlier = create("/api/sales-invoices", invoice(customer, y + "-01-10", "USD", "{\"description\":\"x\"}"));
		List<Integer> order = new ArrayList<>();
		for (JsonNode d : read("/api/sales-invoices")) {
			int n = d.get("id").asInt();
			if (n == id || n == later || n == earlier) {
				order.add(n);
			}
		}
		assertThat(order).containsExactly(later, id, earlier);
	}

	@Test
	void draftValidation() throws Exception {
		int customer = customer(account("asset"));
		String ok = invoice(customer, y + "-02-01", "USD", "{\"description\":\"x\"}");
		assertJsonError(postJson("/api/sales-invoices", ok.replaceFirst("\"invoice_number\":\"[^\"]+\"", "\"invoice_number\":\"\""), session), 400);
		assertJsonError(postJson("/api/sales-invoices", ok.replace("\"customer_id\":" + customer, "\"customer_id\":0"), session), 400);
		assertJsonError(postJson("/api/sales-invoices", invoice(customer, "", "USD", ""), session), 400);
		assertJsonError(postJson("/api/sales-invoices", invoice(customer, y + "-02-01", "", ""), session), 400);
		assertJsonError(postJson("/api/sales-invoices", invoice(customer, y + "-02-01", "USD", "{\"description\":\"\"}"), session), 400);
		assertJsonError(postJson("/api/sales-invoices", ok.replace("\"customer_id\":" + customer, "\"customer_id\":999999"), session), 422);
		assertJsonError(postJson("/api/sales-invoices", invoice(customer, y + "-02-01", "USD", "{\"description\":\"x\",\"quantity\":\"0\"}"), session), 422);
		assertJsonError(postJson("/api/sales-invoices", invoice(customer, y + "-02-01", "ZZZ", ""), session), 422);
		assertJsonError(postJson("/api/sales-invoices", invoice(customer, y + "-02-30", "USD", ""), session), 422);
		assertJsonError(postJson("/api/sales-invoices", invoice(customer, y + "-02-01", "USD", "{\"description\":\"x\",\"unit_price\":\"lots\"}"), session), 422);
		assertJsonError(postJson("/api/sales-invoices", ok.replace("\"currency_code\"", "\"due_date\":\"" + y + "-01-01\",\"currency_code\""), session), 422);

		assertJsonError(sendJson("PUT", "/api/sales-invoices/999999", ok, session), 404);
		assertJsonError(get("/api/sales-invoices/999999/lines", session), 404);
		assertJsonError(sendJson("DELETE", "/api/sales-invoices/999999", "", session), 404);
	}

	@Test
	void postingCreatesTheMonthsPeriodAndUnpostingReverses() throws Exception {
		int ar = account("asset");
		int income = account("revenue");
		int customer = customer(ar);
		String number = uniq("INV");
		String body = "{\"invoice_number\":\"" + number + "\",\"customer_id\":" + customer + ",\"invoice_date\":\"" + y
				+ "-03-15\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"Work\",\"quantity\":\"4\",\"unit_price\":\"25\",\"revenue_account_id\":"
				+ income + "},{\"description\":\"Taxed\",\"unit_price\":\"100\",\"revenue_account_id\":" + income
				+ ",\"tax_code\":\"STD\",\"tax_rate\":\"10\"}]}";
		int id = create("/api/sales-invoices", body);
		int tax = jdbc.sql("SELECT tax_account_id FROM tax_codes WHERE code = 'STD'").query(Integer.class).single();

		int entry = post("sales-invoices", id);
		JsonNode e = read("/api/journal-entries/" + entry);
		assertThat(e.get("entry_date").asString()).isEqualTo(y + "-03-15");
		assertThat(e.get("reference").asString()).isEqualTo(number);
		assertThat(e.get("status").asString()).isEqualTo("posted");
		assertThat(dec(e.get("exchange_rate"))).isEqualTo("1");
		assertThat(lines(entry)).containsExactlyInAnyOrder(ar + " dr 210 210", income + " cr 200 200", tax + " cr 10 10");

		assertThat(jdbc.sql("SELECT start_date::text || ' ' || end_date::text || ' ' || status FROM accounting_periods WHERE name = ?")
				.param(y + "-03").query(String.class).single()).isEqualTo(y + "-03-01 " + y + "-03-31 open");

		JsonNode inv = read("/api/sales-invoices/" + id);
		assertThat(inv.get("status").asString()).isEqualTo("posted");
		assertThat(inv.get("journal_entry_id").asInt()).isEqualTo(entry);
		assertJsonError(postJson("/api/sales-invoices/" + id + "/post", "", session), 409);
		assertJsonError(sendJson("PUT", "/api/sales-invoices/" + id, body, session), 409);
		assertJsonError(sendJson("DELETE", "/api/sales-invoices/" + id, "", session), 409);
		assertThat(read("/api/accounts/" + ar + "/ledger")).hasSize(1);

		assertJsonError(postJson("/api/sales-invoices/" + id + "/unpost", "", session), 403);
		HttpResponse<String> unposted = postJson("/api/sales-invoices/" + id + "/unpost", "", adminSession);
		assertThat(unposted.statusCode()).as(unposted.body()).isEqualTo(200);
		int reversal = json(unposted.body()).get("reversal_entry_id").asInt();
		assertThat(lines(reversal)).containsExactlyInAnyOrder(ar + " cr 210 210", income + " dr 200 200", tax + " dr 10 10");
		assertThat(read("/api/journal-entries/" + entry).get("status").asString()).isEqualTo("posted");
		assertThat(read("/api/sales-invoices/" + id).get("status").asString()).isEqualTo("draft");
		assertJsonError(postJson("/api/sales-invoices/" + id + "/unpost", "", adminSession), 409);

		int again = post("sales-invoices", id);
		assertThat(again).isNotIn(entry, reversal);
		assertJsonError(sendJson("PUT", "/api/settings", "{\"base_currency\":\"EUR\"}", adminSession), 422);
		JsonNode row = null;
		for (JsonNode r : read("/api/trial-balance")) {
			if (r.get("account_id").asInt() == ar) {
				row = r;
			}
		}
		assertThat(dec(row.get("total_debit"))).isEqualTo("420");
		assertThat(dec(row.get("total_credit"))).isEqualTo("210");
		assertThat(dec(row.get("balance"))).isEqualTo("210");
	}

	@Test
	void foreignCurrencyAndNegativeNets() throws Exception {
		jdbc.sql("INSERT INTO exchange_rates (currency_code, rate_date, rate) VALUES ('GBP', ?::date, 1.123456)")
				.param(y + "-01-01").update();
		int ar = account("asset");
		int income = account("revenue");
		int discount = account("revenue");
		int id = create("/api/sales-invoices", invoice(customer(ar), y + "-03-01", "GBP",
				"{\"description\":\"Goods\",\"unit_price\":\"100\",\"revenue_account_id\":" + income + "},"
						+ "{\"description\":\"Discount\",\"unit_price\":\"-10\",\"revenue_account_id\":" + discount + "}"));
		int entry = post("sales-invoices", id);
		assertThat(dec(read("/api/journal-entries/" + entry).get("exchange_rate"))).isEqualTo("1.123456");
		// 100 × 1.123456 = 112.3456 and 10 × 1.123456 = 11.2346; A/R takes their net.
		assertThat(lines(entry)).containsExactlyInAnyOrder(ar + " dr 90 101.111", income + " cr 100 112.3456",
				discount + " dr 10 11.2346");

		// Before any GBP rate, posting is refused.
		int early = create("/api/sales-invoices", invoice(customer(ar), (y - 300) + "-03-01", "GBP",
				"{\"description\":\"x\",\"unit_price\":\"1\",\"revenue_account_id\":" + income + "}"));
		create("/api/fiscal-years", "{\"name\":\"FY-early-" + y + "\",\"start_date\":\"" + (y - 300) + "-01-01\",\"end_date\":\"" + (y - 300) + "-12-31\"}");
		assertJsonError(postJson("/api/sales-invoices/" + early + "/post", "", session), 422);
		assertThat(jdbc.sql("SELECT count(*) FROM accounting_periods WHERE name = ?").param((y - 300) + "-03")
				.query(Integer.class).single()).as("a refused posting leaves no period behind").isZero();
	}

	@Test
	void billsAndCreditNotesPostOnTheirOwnSides() throws Exception {
		int ap = account("liability");
		int expense = account("expense");
		int rebate = account("expense");
		int inventory = account("asset");
		int product = create("/api/products", "{\"sku\":\"" + uniq("SKU") + "\",\"name\":\"Stocked\",\"inventory_account_id\":" + inventory + "}");
		int supplier = supplier(ap);
		String number = uniq("BILL");
		String bill = "{\"bill_number\":\"" + number + "\",\"supplier_id\":" + supplier + ",\"bill_date\":\"" + y
				+ "-04-02\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"Supplies\",\"unit_cost\":\"50\",\"expense_account_id\":"
				+ expense + "},{\"description\":\"Rebate\",\"unit_cost\":\"-5\",\"expense_account_id\":" + rebate
				+ "},{\"description\":\"Stock\",\"quantity\":\"10\",\"unit_cost\":\"3\",\"product_id\":" + product + "}]}";
		int id = create("/api/purchase-bills", bill);
		assertJsonError(postJson("/api/purchase-bills", bill, session), 409);
		create("/api/purchase-bills", bill.replace("\"supplier_id\":" + supplier, "\"supplier_id\":" + supplier(ap)));
		assertThat(read("/api/purchase-bills/" + id + "/lines").get(0).has("unit_cost")).isTrue();
		assertThat(lines(post("purchase-bills", id))).containsExactlyInAnyOrder(expense + " dr 50 50",
				rebate + " cr 5 5", inventory + " dr 30 30", ap + " cr 75 75");

		int ar = account("asset");
		int income = account("revenue");
		int note = create("/api/sales-credit-notes", "{\"credit_note_number\":\"" + uniq("SCN") + "\",\"customer_id\":"
				+ customer(ar) + ",\"credit_note_date\":\"" + y + "-05-01\",\"currency_code\":\"USD\",\"lines\":["
				+ "{\"description\":\"Return\",\"unit_price\":\"40\",\"revenue_account_id\":" + income + "}]}");
		JsonNode n = read("/api/sales-credit-notes/" + note);
		assertThat(n.get("application_status").asString()).isEqualTo("open");
		assertThat(n.has("due_date")).isFalse();
		assertThat(lines(post("sales-credit-notes", note))).containsExactlyInAnyOrder(income + " dr 40 40", ar + " cr 40 40");

		int supplierCredit = create("/api/purchase-credit-notes", "{\"credit_note_number\":\"" + uniq("PCN") + "\",\"supplier_id\":"
				+ supplier + ",\"credit_note_date\":\"" + y + "-05-02\",\"currency_code\":\"USD\",\"lines\":["
				+ "{\"description\":\"Credit\",\"unit_cost\":\"15\",\"expense_account_id\":" + expense + "}]}");
		assertThat(lines(post("purchase-credit-notes", supplierCredit))).containsExactlyInAnyOrder(ap + " dr 15 15",
				expense + " cr 15 15");
		HttpResponse<String> r = postJson("/api/purchase-credit-notes/" + supplierCredit + "/unpost", "", adminSession);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
	}

	@Test
	void postingRefusals() throws Exception {
		int ar = account("asset");
		int income = account("revenue");
		int customer = customer(ar);
		String line = "{\"description\":\"x\",\"unit_price\":\"5\",\"revenue_account_id\":" + income + "}";

		assertJsonError(postJson("/api/sales-invoices/999999/post", "", session), 404);
		assertJsonError(postJson("/api/sales-invoices/0/post", "", session), 400);
		assertJsonError(postJson("/api/sales-invoices/" + create("/api/sales-invoices", invoice(customer, y + "-02-10", "USD", "")) + "/post", "", session), 422);
		assertJsonError(postJson("/api/sales-invoices/" + create("/api/sales-invoices", invoice(customer, y + "-02-10", "USD",
				"{\"description\":\"Refund\",\"quantity\":\"-1\",\"unit_price\":\"5\",\"revenue_account_id\":" + income + "}")) + "/post", "", session), 422);

		int noAr = create("/api/customers", "{\"organization_id\":" + org() + "}");
		assertJsonError(postJson("/api/sales-invoices/" + create("/api/sales-invoices", invoice(noAr, y + "-02-10", "USD", line)) + "/post", "", session), 422);
		assertJsonError(postJson("/api/sales-invoices/" + create("/api/sales-invoices", invoice(customer, y + "-02-10", "USD",
				"{\"description\":\"No account\",\"unit_price\":\"5\"}")) + "/post", "", session), 422);
		assertJsonError(postJson("/api/sales-invoices/" + create("/api/sales-invoices", invoice(customer, y + "-02-10", "USD",
				"{\"description\":\"Taxed\",\"unit_price\":\"5\",\"revenue_account_id\":" + income + ",\"tax_code\":\"ZERO\",\"tax_rate\":\"5\"}")) + "/post", "", session), 422);

		// Outside every fiscal year, and in a closed period.
		assertJsonError(postJson("/api/sales-invoices/" + create("/api/sales-invoices", invoice(customer, (y + 200) + "-02-10", "USD", line)) + "/post", "", session), 422);
		jdbc.sql("INSERT INTO accounting_periods (fiscal_year_id, name, start_date, end_date, status) "
				+ "SELECT id, 'Sep', ?::date, ?::date, 'closed' FROM fiscal_years WHERE name = ?")
				.params(y + "-09-01", y + "-09-30", "FY-doc-" + y).update();
		assertJsonError(postJson("/api/sales-invoices/" + create("/api/sales-invoices", invoice(customer, y + "-09-15", "USD", line)) + "/post", "", session), 422);

		// Unposting into a period closed since posting.
		int october = create("/api/sales-invoices", invoice(customer, y + "-10-15", "USD", line));
		post("sales-invoices", october);
		jdbc.sql("UPDATE accounting_periods SET status = 'closed' WHERE name = ?").param(y + "-10").update();
		assertJsonError(postJson("/api/sales-invoices/" + october + "/unpost", "", adminSession), 422);
		assertThat(read("/api/sales-invoices/" + october).get("status").asString()).isEqualTo("posted");
	}

	@Test
	void journalEntryNotFound() throws Exception {
		assertJsonError(get("/api/journal-entries/999999", session), 404);
		assertJsonError(get("/api/journal-entries/abc", session), 400);
	}
}
