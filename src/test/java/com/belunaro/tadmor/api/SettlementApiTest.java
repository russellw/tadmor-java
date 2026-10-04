package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** Payments (spec/api.md §5.9), applications (spec/domain.md §5), and realized FX (§7.3). */
class SettlementApiTest extends PostingTest {

	private int invoice(int customer, String date, String currency, String amount, int income) throws Exception {
		return create("/api/sales-invoices", "{\"invoice_number\":\"" + uniq("INV") + "\",\"customer_id\":" + customer
				+ ",\"invoice_date\":\"" + date + "\",\"currency_code\":\"" + currency + "\",\"lines\":[{\"description\":\"x\","
				+ "\"unit_price\":\"" + amount + "\",\"revenue_account_id\":" + income + "}]}");
	}

	private int payment(int customer, String date, String currency, String amount, int bank) throws Exception {
		return create("/api/customer-payments", "{\"customer_id\":" + customer + ",\"payment_date\":\"" + date
				+ "\",\"currency_code\":\"" + currency + "\",\"amount\":\"" + amount + "\",\"deposit_account_id\":" + bank + "}");
	}

	private JsonNode apply(String collection, int id) throws Exception {
		HttpResponse<String> r = postJson("/api/" + collection + "/" + id + "/apply", "", session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		return json(r.body()).get("applications");
	}

	@Test
	void paymentsApplyOldestFirstAndUnpostingReopens() throws Exception {
		int ar = account("asset");
		int income = account("revenue");
		int bank = account("asset");
		int customer = customer(ar);
		int inv1 = invoice(customer, y + "-01-10", "USD", "100", income);
		int inv2 = invoice(customer, y + "-02-10", "USD", "80", income);
		int inv3 = invoice(customer, y + "-03-10", "USD", "50", income);
		invoice(customer, y + "-01-05", "USD", "999", income); // a draft is never open
		for (int id : new int[] { inv3, inv1, inv2 }) {
			post("sales-invoices", id);
		}

		assertJsonError(postJson("/api/customer-payments", "{\"customer_id\":" + customer + ",\"payment_date\":\"" + y
				+ "-04-01\",\"currency_code\":\"USD\"}", session), 400);
		assertJsonError(postJson("/api/customer-payments", "{\"customer_id\":" + customer + ",\"payment_date\":\"" + y
				+ "-04-01\",\"currency_code\":\"USD\",\"amount\":\"0\"}", session), 422);
		assertJsonError(postJson("/api/customer-payments", "{\"customer_id\":" + customer + ",\"payment_date\":\"" + y
				+ "-04-01\",\"currency_code\":\"USD\",\"amount\":\"5\",\"method\":\"barter\"}", session), 422);
		int noBank = create("/api/customer-payments", "{\"customer_id\":" + customer + ",\"payment_date\":\"" + y
				+ "-04-01\",\"currency_code\":\"USD\",\"amount\":\"5\"}");
		assertJsonError(postJson("/api/customer-payments/" + noBank + "/post", "", session), 422);

		int pay = payment(customer, y + "-04-01", "USD", "150", bank);
		JsonNode p = read("/api/customer-payments/" + pay);
		assertThat(p.get("status").asString()).isEqualTo("draft");
		assertThat(dec(p.get("unapplied"))).isEqualTo("150");
		assertThat(p.get("deposit_account_id").asInt()).isEqualTo(bank);
		assertJsonError(postJson("/api/customer-payments/" + pay + "/apply", "", session), 409);

		int entry = post("customer-payments", pay);
		assertThat(lines(entry)).containsExactlyInAnyOrder(bank + " dr 150 150", ar + " cr 150 150");
		assertThat(read("/api/journal-entries/" + entry).get("reference").isNull()).isTrue();

		JsonNode apps = apply("customer-payments", pay);
		assertThat(apps).hasSize(2);
		assertThat(apps.get(0).get("document_id").asInt()).isEqualTo(inv1);
		assertThat(dec(apps.get(0).get("amount_applied"))).isEqualTo("100");
		assertThat(apps.get(1).get("document_id").asInt()).isEqualTo(inv2);
		assertThat(dec(apps.get(1).get("amount_applied"))).isEqualTo("50");
		assertThat(read("/api/sales-invoices/" + inv1).get("payment_status").asString()).isEqualTo("paid");
		assertThat(read("/api/sales-invoices/" + inv2).get("payment_status").asString()).isEqualTo("partial");
		assertThat(dec(read("/api/sales-invoices/" + inv2).get("balance"))).isEqualTo("30");
		assertThat(read("/api/sales-invoices/" + inv3).get("payment_status").asString()).isEqualTo("unpaid");
		assertThat(dec(read("/api/customer-payments/" + pay).get("unapplied"))).isEqualTo("0");
		assertThat(apply("customer-payments", pay)).isEmpty();

		JsonNode list = read("/api/customer-payments/" + pay + "/applications");
		assertThat(list).hasSize(2);
		assertThat(list.get(0).get("document_number").asString())
				.isEqualTo(read("/api/sales-invoices/" + inv1).get("invoice_number").asString());
		assertJsonError(get("/api/customer-payments/999999/applications", session), 404);

		// A document with applications cannot be unposted; a payment can, and takes its applications with it.
		assertJsonError(postJson("/api/sales-invoices/" + inv1 + "/unpost", "", adminSession), 409);
		assertJsonError(postJson("/api/customer-payments/" + pay + "/unpost", "", session), 403);
		HttpResponse<String> r = postJson("/api/customer-payments/" + pay + "/unpost", "", adminSession);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		assertThat(lines(json(r.body()).get("reversal_entry_id").asInt()))
				.containsExactlyInAnyOrder(bank + " cr 150 150", ar + " dr 150 150");
		assertThat(read("/api/customer-payments/" + pay + "/applications")).isEmpty();
		assertThat(read("/api/sales-invoices/" + inv1).get("payment_status").asString()).isEqualTo("unpaid");
		assertThat(postJson("/api/sales-invoices/" + inv1 + "/unpost", "", adminSession).statusCode()).isEqualTo(200);
		assertThat(sendJson("DELETE", "/api/customer-payments/" + pay, "", session).statusCode()).isEqualTo(204);
	}

	@Test
	void creditNotesAndPaymentsShareAnInvoicesAvailability() throws Exception {
		int ar = account("asset");
		int income = account("revenue");
		int customer = customer(ar);
		int inv = invoice(customer, y + "-01-10", "USD", "220", income);
		post("sales-invoices", inv);

		int note = create("/api/sales-credit-notes", "{\"credit_note_number\":\"" + uniq("CN") + "\",\"customer_id\":" + customer
				+ ",\"credit_note_date\":\"" + y + "-02-01\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"Return\","
				+ "\"unit_price\":\"55\",\"revenue_account_id\":" + income + "}]}");
		assertJsonError(postJson("/api/sales-credit-notes/" + note + "/apply", "", session), 409);
		post("sales-credit-notes", note);
		JsonNode apps = apply("sales-credit-notes", note);
		assertThat(apps).hasSize(1);
		assertThat(dec(apps.get(0).get("amount_applied"))).isEqualTo("55");
		assertThat(read("/api/sales-credit-notes/" + note).get("application_status").asString()).isEqualTo("applied");
		assertThat(dec(read("/api/sales-invoices/" + inv).get("balance"))).isEqualTo("165");
		assertThat(read("/api/sales-credit-notes/" + note + "/applications")).hasSize(1);

		int pay = payment(customer, y + "-03-01", "USD", "500", account("asset"));
		post("customer-payments", pay);
		apps = apply("customer-payments", pay);
		assertThat(dec(apps.get(0).get("amount_applied"))).isEqualTo("165");
		assertThat(read("/api/sales-invoices/" + inv).get("payment_status").asString()).isEqualTo("paid");
		assertThat(dec(read("/api/customer-payments/" + pay).get("unapplied"))).isEqualTo("335");
		assertJsonError(postJson("/api/sales-credit-notes/" + note + "/unpost", "", adminSession), 409);
	}

	@Test
	void supplierPaymentsMirrorCustomerPayments() throws Exception {
		int ap = account("liability");
		int expense = account("expense");
		int bank = account("asset");
		int supplier = supplier(ap);
		int bill = create("/api/purchase-bills", "{\"bill_number\":\"" + uniq("B") + "\",\"supplier_id\":" + supplier
				+ ",\"bill_date\":\"" + y + "-01-10\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"x\","
				+ "\"unit_cost\":\"60\",\"expense_account_id\":" + expense + "}]}");
		post("purchase-bills", bill);
		int pay = create("/api/supplier-payments", "{\"supplier_id\":" + supplier + ",\"payment_date\":\"" + y
				+ "-03-01\",\"currency_code\":\"USD\",\"amount\":\"90\",\"payment_account_id\":" + bank + "}");
		assertThat(read("/api/supplier-payments/" + pay).get("payment_account_id").asInt()).isEqualTo(bank);
		assertThat(lines(post("supplier-payments", pay))).containsExactlyInAnyOrder(ap + " dr 90 90", bank + " cr 90 90");
		JsonNode apps = apply("supplier-payments", pay);
		assertThat(apps).hasSize(1);
		assertThat(dec(apps.get(0).get("amount_applied"))).isEqualTo("60");
		assertThat(read("/api/purchase-bills/" + bill).get("payment_status").asString()).isEqualTo("paid");
		assertThat(dec(read("/api/supplier-payments/" + pay).get("unapplied"))).isEqualTo("30");
	}

	@Test
	void settlingAtADifferentRateRealizesExchange() throws Exception {
		jdbc.sql("INSERT INTO exchange_rates (currency_code, rate_date, rate) VALUES ('AUD', ?::date, 1.2), ('AUD', ?::date, 1.3)")
				.params(y + "-01-01", y + "-03-01").update();
		int ar = account("asset");
		int income = account("revenue");
		int customer = customer(ar);
		int fx = jdbc.sql("SELECT fx_gain_loss_account_id FROM gl_settings").query(Integer.class).single();

		int inv = invoice(customer, y + "-02-01", "AUD", "100", income);
		post("sales-invoices", inv);
		int pay = payment(customer, y + "-04-01", "AUD", "100", account("asset"));
		post("customer-payments", pay);

		// Without an FX account the apply is refused, and applies nothing.
		jdbc.sql("UPDATE gl_settings SET fx_gain_loss_account_id = NULL").update();
		try {
			assertJsonError(postJson("/api/customer-payments/" + pay + "/apply", "", session), 422);
			assertThat(read("/api/customer-payments/" + pay + "/applications")).isEmpty();
		} finally {
			jdbc.sql("UPDATE gl_settings SET fx_gain_loss_account_id = ?").param(fx).update();
		}

		assertThat(apply("customer-payments", pay)).hasSize(1);
		// round(100 × 1.3) − round(100 × 1.2) = 10: a gain, Dr A/R, Cr FX, in base on the payment date.
		Integer fxEntry = jdbc.sql("SELECT fx_journal_entry_id FROM payment_applications WHERE payment_id = ?").param(pay)
				.query(Integer.class).single();
		assertThat(lines(fxEntry)).containsExactlyInAnyOrder(ar + " dr 10 10", fx + " cr 10 10");
		JsonNode e = read("/api/journal-entries/" + fxEntry);
		assertThat(e.get("entry_date").asString()).isEqualTo(y + "-04-01");
		assertThat(dec(e.get("exchange_rate"))).isEqualTo("1");

		assertThat(postJson("/api/customer-payments/" + pay + "/unpost", "", adminSession).statusCode()).isEqualTo(200);
		assertThat(jdbc.sql("SELECT count(*) FROM journal_entries WHERE reverses_entry_id = ?").param(fxEntry)
				.query(Integer.class).single()).as("the FX entry is reversed with the payment").isEqualTo(1);
	}
}
