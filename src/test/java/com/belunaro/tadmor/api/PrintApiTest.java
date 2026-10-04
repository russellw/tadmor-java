package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

/** spec/api.md §5.11, with email disabled as in the conformance configuration. */
class PrintApiTest extends PostingTest {

	@Test
	void printablesDownloadAsPdf() throws Exception {
		int org = create("/api/organizations", "{\"name\":\"Printed Org " + System.nanoTime() + "\"}");
		int customer = create("/api/customers", "{\"organization_id\":" + org + "}");
		int supplier = supplier(account("liability"));
		String number = "INV/" + System.nanoTime() + " 01";
		int inv = create("/api/sales-invoices", "{\"invoice_number\":\"" + number + "\",\"customer_id\":" + customer
				+ ",\"invoice_date\":\"" + y + "-04-01\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"Item\","
				+ "\"quantity\":\"2\",\"unit_price\":\"12.50\"}]}");
		int po = create("/api/purchase-orders", "{\"order_number\":\"PO" + System.nanoTime() + "\",\"supplier_id\":" + supplier
				+ ",\"order_date\":\"" + y + "-04-01\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"Item\"}]}");

		HttpResponse<String> r = get("/api/sales-invoices/" + inv + "/pdf", session);
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(r.headers().firstValue("Content-Type")).hasValue("application/pdf");
		assertThat(r.headers().firstValue("Content-Disposition"))
				.hasValue("inline; filename=\"invoice-" + number.replace('/', '-').replace(' ', '-') + ".pdf\"");
		assertThat(r.body()).startsWith("%PDF-");
		assertThat(get("/api/purchase-orders/" + po + "/pdf", session).headers().firstValue("Content-Disposition"))
				.hasValueSatisfying(d -> assertThat(d).startsWith("inline; filename=\"purchase-order-PO"));
		assertJsonError(get("/api/sales-invoices/999999/pdf", session), 404);
		assertJsonError(get("/api/sales-invoices/abc/pdf", session), 400);
		assertJsonError(get("/api/stock-movements/1/pdf", session), 404);

		// Email: an explicit recipient reaches the disabled mailer; otherwise the organization's email is needed.
		String email = "/api/sales-invoices/" + inv + "/email";
		assertJsonError(postJson(email, "{\"to\":[\"someone@example.com\"]}", session), 501);
		assertJsonError(postJson("/api/sales-invoices/999999/email", "{\"to\":[\"someone@example.com\"]}", session), 404);
		assertJsonError(postJson(email, "", session), 422);
		assertJsonError(postJson(email, "{\"to\":[]}", session), 422);
		assertJsonError(postJson(email, "{oops", session), 400);
		jdbc.sql("UPDATE organizations SET email = 'ap@customer.example' WHERE id = ?").param(org).update();
		assertJsonError(postJson(email, "", session), 501);
	}
}
