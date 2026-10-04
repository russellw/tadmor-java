package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** Reports (spec/api.md §5.14, spec/domain.md §10) and year-end close (§9.3). */
class ReportsAndYearEndApiTest extends PostingTest {

	private int invoice(int customer, String date, String due, String amount, int income) throws Exception {
		return create("/api/sales-invoices", "{\"invoice_number\":\"" + uniq("INV") + "\",\"customer_id\":" + customer
				+ ",\"invoice_date\":\"" + date + "\"" + (due == null ? "" : ",\"due_date\":\"" + due + "\"")
				+ ",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"x\",\"unit_price\":\"" + amount
				+ "\",\"revenue_account_id\":" + income + "}]}");
	}

	private int bill(int supplier, String date, String amount, int expense) throws Exception {
		return create("/api/purchase-bills", "{\"bill_number\":\"" + uniq("B") + "\",\"supplier_id\":" + supplier
				+ ",\"bill_date\":\"" + date + "\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"x\",\"unit_cost\":\""
				+ amount + "\",\"expense_account_id\":" + expense + "}]}");
	}

	private static JsonNode row(JsonNode rows, int accountId) {
		for (JsonNode r : rows) {
			if (r.get("account_id").asInt() == accountId) {
				return r;
			}
		}
		throw new AssertionError("no row for account " + accountId);
	}

	private static BigDecimal num(JsonNode n) {
		return new BigDecimal(n.asString());
	}

	@Test
	void statementsAgreeWithEachOther() throws Exception {
		int ar = account("asset");
		int income = account("revenue");
		int ap = account("liability");
		int expense = account("expense");
		int bank = create("/api/accounts", "{\"code\":\"C" + System.nanoTime() + "\",\"name\":\"Bank\",\"account_type\":\"asset\","
				+ "\"is_postable\":true,\"is_cash\":true}");
		int customer = customer(ar);
		int inv = invoice(customer, y + "-02-01", null, "1000", income);
		post("sales-invoices", inv);
		post("purchase-bills", bill(supplier(ap), y + "-02-05", "300", expense));
		int pay = create("/api/customer-payments", "{\"customer_id\":" + customer + ",\"payment_date\":\"" + y
				+ "-03-01\",\"currency_code\":\"USD\",\"amount\":\"600\",\"deposit_account_id\":" + bank + "}");
		post("customer-payments", pay);
		String range = "from=" + y + "-01-01&to=" + y + "-12-31";

		JsonNode pl = read("/api/profit-and-loss?" + range);
		assertThat(dec(row(pl, income).get("amount"))).isEqualTo("1000");
		assertThat(dec(row(pl, expense).get("amount"))).isEqualTo("300");
		assertThat(read("/api/profit-and-loss?from=" + y + "-03-01&to=" + y + "-12-31").findValues("account_id"))
				.noneMatch(n -> n.asInt() == income);

		// Within this test's year: assets = liabilities + equity + current earnings, for this test's accounts.
		JsonNode cf = read("/api/cash-flow?" + range);
		assertThat(dec(cf.get("net_income"))).isEqualTo("700");
		assertThat(dec(row(cf.get("rows"), ar).get("amount"))).as("A/R grew by 400: a use of cash").isEqualTo("-400");
		assertThat(dec(row(cf.get("rows"), ap).get("amount"))).isEqualTo("300");
		assertThat(row(cf.get("rows"), ar).get("activity").asString()).isEqualTo("operating");
		assertThat(cf.get("rows").findValues("account_id")).noneMatch(n -> n.asInt() == bank);
		BigDecimal sum = num(cf.get("net_income"));
		for (JsonNode r : cf.get("rows")) {
			sum = sum.add(num(r.get("amount")));
		}
		assertThat(sum).isEqualByComparingTo(num(cf.get("net_cash_flow")));
		assertThat(num(cf.get("opening_cash")).add(num(cf.get("net_cash_flow")))).isEqualByComparingTo(num(cf.get("closing_cash")));

		JsonNode bs = read("/api/balance-sheet?as_of=" + y + "-12-31");
		assertThat(dec(row(bs.get("rows"), ar).get("amount"))).isEqualTo("400");
		assertThat(dec(row(bs.get("rows"), ap).get("amount"))).isEqualTo("300");
		assertThat(dec(row(bs.get("rows"), bank).get("amount"))).isEqualTo("600");
		BigDecimal assets = BigDecimal.ZERO;
		BigDecimal claims = num(bs.get("current_earnings"));
		for (JsonNode r : bs.get("rows")) {
			if (r.get("account_type").asString().equals("asset")) {
				assets = assets.add(num(r.get("amount")));
			} else {
				claims = claims.add(num(r.get("amount")));
			}
		}
		assertThat(assets).as("the accounting identity, over the whole ledger").isEqualByComparingTo(claims);

		assertJsonError(get("/api/profit-and-loss?from=yesterday", session), 400);
		assertJsonError(get("/api/balance-sheet?as_of=2026-13-01", session), 400);
		assertJsonError(get("/api/cash-flow?to=soon", session), 400);
	}

	@Test
	void agingBucketsByDueDateAgainstToday() throws Exception {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		create("/api/fiscal-years", "{\"name\":\"FY-aging-" + System.nanoTime() + "\",\"start_date\":\"" + today.minusDays(200)
				+ "\",\"end_date\":\"" + today.plusDays(30) + "\"}");
		int income = account("revenue");
		int customer = customer(account("asset"));
		LocalDate issued = today.minusDays(150);
		for (Object[] due : new Object[][] { { today, "10" }, { today.minusDays(1), "20" }, { today.minusDays(31), "30" },
				{ today.minusDays(61), "40" }, { today.minusDays(91), "50" } }) {
			post("sales-invoices", invoice(customer, issued.toString(), due[0].toString(), (String) due[1], income));
		}
		post("sales-invoices", invoice(customer, issued.toString(), null, "5", income));
		invoice(customer, issued.toString(), null, "999", income); // drafts do not age

		JsonNode aging = null;
		for (JsonNode r : read("/api/ar-aging")) {
			if (r.get("party_id").asInt() == customer) {
				aging = r;
			}
		}
		assertThat(aging.get("party_name").asString()).startsWith("Org ");
		assertThat(dec(aging.get("total_outstanding"))).isEqualTo("155");
		assertThat(dec(aging.get("not_yet_due"))).isEqualTo("15");
		assertThat(dec(aging.get("days_1_30"))).isEqualTo("20");
		assertThat(dec(aging.get("days_31_60"))).isEqualTo("30");
		assertThat(dec(aging.get("days_61_90"))).isEqualTo("40");
		assertThat(dec(aging.get("days_over_90"))).isEqualTo("50");
		assertThat(read("/api/ap-aging").isArray()).isTrue();
	}

	@Test
	void yearEndCloseSweepsIncomeAndReopenReverses() throws Exception {
		int ar = account("asset");
		int income = account("revenue");
		int ap = account("liability");
		int expense = account("expense");
		int retained = account("equity");
		int y1 = create("/api/fiscal-years", "{\"name\":\"Y1-" + System.nanoTime() + "\",\"start_date\":\"1901-01-01\",\"end_date\":\"1901-12-31\"}");
		int customer = customer(ar);
		post("sales-invoices", invoice(customer, "1901-06-15", null, "1000", income));
		post("purchase-bills", bill(supplier(ap), "1901-07-10", "400", expense));

		String close = "/api/fiscal-years/" + y1 + "/close";
		assertJsonError(postJson(close, "{\"retained_earnings_account_id\":" + account("asset") + "}", adminSession), 422);
		assertJsonError(postJson(close, "{}", adminSession), 400);
		assertJsonError(postJson("/api/fiscal-years/999999/close", "{\"retained_earnings_account_id\":" + retained + "}", adminSession), 404);
		assertJsonError(postJson("/api/fiscal-years/" + y1 + "/reopen", "", adminSession), 409);
		assertJsonError(postJson(close, "{\"retained_earnings_account_id\":" + retained + "}", session), 403);

		HttpResponse<String> r = postJson(close, "{\"retained_earnings_account_id\":" + retained + "}", adminSession);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		int closing = json(r.body()).get("closing_entry_id").asInt();
		int y2 = json(r.body()).get("next_fiscal_year_id").asInt();
		JsonNode e = read("/api/journal-entries/" + closing);
		assertThat(e.get("entry_date").asString()).isEqualTo("1901-12-31");
		assertThat(lines(closing)).containsExactlyInAnyOrder(income + " dr 1000 1000", expense + " cr 400 400",
				retained + " cr 600 600");
		JsonNode next = read("/api/fiscal-years/" + y2);
		assertThat(next.get("name").asString()).isEqualTo("FY1902");
		assertThat(next.get("start_date").asString()).isEqualTo("1902-01-01");
		assertThat(next.get("end_date").asString()).isEqualTo("1902-12-31");
		assertThat(read("/api/fiscal-years/" + y1).get("status").asString()).isEqualTo("closed");
		assertThat(jdbc.sql("SELECT count(*) FROM accounting_periods WHERE fiscal_year_id = ? AND status = 'open'").param(y1)
				.query(Integer.class).single()).isZero();
		assertJsonError(postJson("/api/sales-invoices/" + invoice(customer, "1901-08-01", null, "5", income) + "/post", "", session), 422);
		assertJsonError(postJson(close, "{\"retained_earnings_account_id\":" + retained + "}", adminSession), 409);

		// The income statement ignores the closing entry; the balance sheet does not.
		assertThat(dec(row(read("/api/profit-and-loss?from=1901-01-01&to=1901-12-31"), income).get("amount"))).isEqualTo("1000");
		JsonNode bs = read("/api/balance-sheet?as_of=1901-12-31");
		assertThat(dec(row(bs.get("rows"), retained).get("amount"))).isEqualTo("600");

		// An empty year closes with nothing to sweep.
		r = postJson("/api/fiscal-years/" + y2 + "/close", "{\"retained_earnings_account_id\":" + retained + "}", adminSession);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		assertThat(json(r.body()).get("closing_entry_id").isNull()).isTrue();
		assertThat(json(r.body()).get("next_fiscal_year_id").isNull()).as("FY1903 is created").isFalse();

		// Reopen newest first.
		assertJsonError(postJson("/api/fiscal-years/" + y1 + "/reopen", "", adminSession), 422);
		r = postJson("/api/fiscal-years/" + y2 + "/reopen", "", adminSession);
		assertThat(json(r.body()).get("reversal_entry_id").isNull()).isTrue();
		r = postJson("/api/fiscal-years/" + y1 + "/reopen", "", adminSession);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		int reversal = json(r.body()).get("reversal_entry_id").asInt();
		assertThat(lines(reversal)).containsExactlyInAnyOrder(income + " cr 1000 1000", expense + " dr 400 400",
				retained + " dr 600 600");
		assertThat(read("/api/fiscal-years/" + y1).get("status").asString()).isEqualTo("open");
		assertJsonError(postJson("/api/fiscal-years/" + y2 + "/close", "{\"retained_earnings_account_id\":" + retained + "}", adminSession), 422);
		assertThat(dec(row(read("/api/balance-sheet?as_of=1901-12-31").get("rows"), retained).get("amount"))).isEqualTo("0");
		post("sales-invoices", invoice(customer, "1901-12-20", null, "5", income));
	}
}
