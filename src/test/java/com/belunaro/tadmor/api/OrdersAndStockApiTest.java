package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** Orders (spec/api.md §5.10, spec/domain.md §6) and stock movements (§5.12). */
class OrdersAndStockApiTest extends PostingTest {

	private record Stocked(int product, int inventory, int cogs) {
	}

	private Stocked stocked() throws Exception {
		int inventory = account("asset");
		int cogs = account("expense");
		int product = create("/api/products", "{\"sku\":\"" + uniq("SKU") + "\",\"name\":\"Stocked\",\"track_inventory\":true,"
				+ "\"inventory_account_id\":" + inventory + ",\"cogs_account_id\":" + cogs + "}");
		return new Stocked(product, inventory, cogs);
	}

	private int warehouse() throws Exception {
		return create("/api/warehouses", "{\"code\":\"" + uniq("WH") + "\",\"name\":\"Main\"}");
	}

	private String movement(int product, int warehouse, String type, String date, String qty, String cost) {
		return "{\"product_id\":" + product + ",\"warehouse_id\":" + warehouse + ",\"movement_type\":\"" + type
				+ "\",\"movement_date\":\"" + date + "\",\"quantity\":\"" + qty + "\",\"unit_cost\":\"" + cost + "\"}";
	}

	private HttpResponse<String> action(String path, String body) throws Exception {
		return postJson(path, body, session);
	}

	private JsonNode lineFor(JsonNode lines, int orderLineId) {
		for (JsonNode l : lines) {
			if (l.get("order_line_id").asInt() == orderLineId) {
				return l;
			}
		}
		throw new AssertionError("no order line " + orderLineId);
	}

	@Test
	void salesOrderLifecycleAndFulfilment() throws Exception {
		int ar = account("asset");
		int income = account("revenue");
		int customer = customer(ar);
		Stocked s = stocked();
		int wh = warehouse();
		create("/api/stock-movements", movement(s.product(), wh, "receipt", y + "-01-02", "10", "4"));

		int empty = create("/api/sales-orders", "{\"order_number\":\"" + uniq("SO") + "\",\"customer_id\":" + customer
				+ ",\"order_date\":\"" + y + "-01-05\",\"currency_code\":\"USD\"}");
		assertJsonError(action("/api/sales-orders/" + empty + "/confirm", ""), 422);
		assertJsonError(postJson("/api/sales-orders", "{\"order_number\":\"" + uniq("SO") + "\",\"customer_id\":" + customer
				+ ",\"order_date\":\"" + y + "-01-05\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"x\",\"quantity\":\"-1\"}]}", session), 422);

		String number = uniq("SO");
		String body = "{\"order_number\":\"" + number + "\",\"customer_id\":" + customer + ",\"order_date\":\"" + y
				+ "-01-10\",\"expected_ship_date\":\"" + y + "-01-20\",\"currency_code\":\"USD\",\"lines\":["
				+ "{\"description\":\"Installation\",\"unit_price\":\"100\",\"revenue_account_id\":" + income + "},"
				+ "{\"description\":\"Widgets\",\"product_id\":" + s.product() + ",\"quantity\":\"6\",\"unit_price\":\"20\",\"revenue_account_id\":" + income + "}]}";
		int so = create("/api/sales-orders", body);
		JsonNode o = read("/api/sales-orders/" + so);
		assertThat(o.get("status").asString()).isEqualTo("draft");
		assertThat(o.get("expected_ship_date").asString()).isEqualTo(y + "-01-20");
		assertThat(o.get("invoiced_status").asString()).isEqualTo("none");
		assertThat(dec(o.get("total"))).isEqualTo("220");
		JsonNode lines = read("/api/sales-orders/" + so + "/lines");
		int service = lines.get(0).get("order_line_id").asInt();
		int widgets = lines.get(1).get("order_line_id").asInt();
		assertThat(dec(lines.get(0).get("qty_to_ship"))).as("services are never shipped").isEqualTo("0");
		assertThat(dec(lines.get(1).get("qty_to_ship"))).isEqualTo("6");

		assertJsonError(action("/api/sales-orders/" + so + "/invoice", "{\"invoice_number\":\"" + uniq("I") + "\",\"invoice_date\":\"" + y + "-01-11\"}"), 409);
		assertThat(action("/api/sales-orders/" + so + "/confirm", "").statusCode()).isEqualTo(204);
		assertJsonError(action("/api/sales-orders/" + so + "/confirm", ""), 409);
		assertJsonError(sendJson("PUT", "/api/sales-orders/" + so, body, session), 409);

		// A partial invoice, which cannot be edited but returns its quantity when deleted.
		HttpResponse<String> r = action("/api/sales-orders/" + so + "/invoice", "{\"invoice_number\":\"" + uniq("I")
				+ "\",\"invoice_date\":\"" + y + "-01-11\",\"lines\":[{\"order_line_id\":" + widgets + ",\"quantity\":\"2\"}]}");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		int partial = json(r.body()).get("invoice_id").asInt();
		JsonNode inv = read("/api/sales-invoices/" + partial);
		assertThat(inv.get("reference").asString()).isEqualTo(number);
		assertThat(dec(inv.get("total"))).isEqualTo("40");
		assertThat(read("/api/sales-invoices/" + partial + "/lines").get(0).get("order_line_id").asInt()).isEqualTo(widgets);
		assertThat(read("/api/sales-orders/" + so).get("invoiced_status").asString()).isEqualTo("partial");
		assertJsonError(sendJson("PUT", "/api/sales-invoices/" + partial, "{\"invoice_number\":\"X\",\"customer_id\":" + customer
				+ ",\"invoice_date\":\"" + y + "-01-11\",\"currency_code\":\"USD\"}", session), 409);
		assertThat(sendJson("DELETE", "/api/sales-invoices/" + partial, "", session).statusCode()).isEqualTo(204);
		assertThat(read("/api/sales-orders/" + so).get("invoiced_status").asString()).isEqualTo("none");

		// Requests are capped at what remains.
		r = action("/api/sales-orders/" + so + "/invoice", "{\"invoice_number\":\"" + uniq("I") + "\",\"invoice_date\":\"" + y
				+ "-01-12\",\"lines\":[{\"order_line_id\":" + widgets + ",\"quantity\":\"99\"},{\"order_line_id\":" + service + ",\"quantity\":\"1\"}]}");
		int full = json(r.body()).get("invoice_id").asInt();
		assertThat(dec(read("/api/sales-invoices/" + full).get("total"))).isEqualTo("220");
		assertThat(read("/api/sales-orders/" + so).get("invoiced_status").asString()).isEqualTo("invoiced");
		assertJsonError(action("/api/sales-orders/" + so + "/invoice", "{\"invoice_number\":\"" + uniq("I") + "\",\"invoice_date\":\"" + y + "-01-12\"}"), 422);
		assertJsonError(action("/api/sales-orders/" + so + "/cancel", ""), 409);

		// Shipped at the warehouse's moving-average cost.
		assertJsonError(action("/api/sales-orders/" + so + "/ship", "{}"), 400);
		r = action("/api/sales-orders/" + so + "/ship", "{\"warehouse_id\":" + wh + ",\"movement_date\":\"" + y + "-01-15\"}");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		JsonNode ids = json(r.body()).get("movement_ids");
		assertThat(ids).hasSize(1);
		JsonNode m = read("/api/stock-movements/" + ids.get(0).asInt());
		assertThat(m.get("movement_type").asString()).isEqualTo("issue");
		assertThat(m.get("source_type").asString()).isEqualTo("sales_order_line");
		assertThat(dec(m.get("quantity"))).isEqualTo("-6");
		assertThat(dec(m.get("unit_cost"))).isEqualTo("4");
		assertThat(read("/api/sales-orders/" + so).get("shipped_status").asString()).isEqualTo("shipped");
		assertJsonError(sendJson("PUT", "/api/stock-movements/" + ids.get(0).asInt(),
				movement(s.product(), wh, "issue", y + "-01-15", "-1", "4"), session), 409);
		assertThat(lines(post("stock-movements", ids.get(0).asInt())))
				.containsExactlyInAnyOrder(s.cogs() + " dr 24 24", s.inventory() + " cr 24 24");
		assertThat(dec(lineFor(read("/api/sales-orders/" + so + "/lines"), widgets).get("qty_shipped"))).isEqualTo("6");

		assertThat(action("/api/sales-orders/" + so + "/close", "").statusCode()).isEqualTo(204);
		assertJsonError(action("/api/sales-orders/" + so + "/close", ""), 409);
		assertJsonError(action("/api/sales-orders/" + so + "/ship", "{\"warehouse_id\":" + wh + "}"), 409);
		assertThat(action("/api/sales-orders/" + empty + "/cancel", "").statusCode()).isEqualTo(204);
		assertJsonError(action("/api/sales-orders/999999/confirm", ""), 404);
	}

	@Test
	void receivingAForeignOrderValuesStockInBase() throws Exception {
		int ap = account("liability");
		int supplier = supplier(ap);
		Stocked s = stocked();
		int wh = warehouse();
		int grni = jdbc.sql("SELECT id FROM accounts WHERE code = '2150'").query(Integer.class).single();
		jdbc.sql("INSERT INTO exchange_rates (currency_code, rate_date, rate) VALUES ('JPY', ?::date, 0.0067)")
				.param(y + "-03-01").update();
		String number = uniq("PO");
		int po = create("/api/purchase-orders", "{\"order_number\":\"" + number + "\",\"supplier_id\":" + supplier
				+ ",\"order_date\":\"" + y + "-02-01\",\"expected_receipt_date\":\"" + y + "-03-10\",\"currency_code\":\"JPY\",\"lines\":["
				+ "{\"description\":\"Widgets\",\"product_id\":" + s.product() + ",\"quantity\":\"4\",\"unit_cost\":\"1500\",\"expense_account_id\":" + grni + "}]}");
		assertThat(read("/api/purchase-orders/" + po).get("received_status").asString()).isEqualTo("none");
		assertThat(action("/api/purchase-orders/" + po + "/confirm", "").statusCode()).isEqualTo(204);

		// Before the first JPY rate nothing can be received.
		assertJsonError(action("/api/purchase-orders/" + po + "/receive", "{\"warehouse_id\":" + wh + ",\"movement_date\":\"" + y + "-02-15\"}"), 422);
		assertThat(read("/api/purchase-orders/" + po).get("received_status").asString()).isEqualTo("none");

		HttpResponse<String> r = action("/api/purchase-orders/" + po + "/receive", "{\"warehouse_id\":" + wh
				+ ",\"movement_date\":\"" + y + "-03-05\"}");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		int receipt = json(r.body()).get("movement_ids").get(0).asInt();
		JsonNode m = read("/api/stock-movements/" + receipt);
		assertThat(m.get("source_type").asString()).isEqualTo("purchase_order_line");
		assertThat(dec(m.get("unit_cost"))).as("1500 × 0.0067").isEqualTo("10.05");
		assertThat(read("/api/purchase-orders/" + po).get("received_status").asString()).isEqualTo("received");

		r = postJson("/api/stock-movements/" + receipt + "/post", "{\"credit_account_id\":" + grni + "}", session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		int entry = json(r.body()).get("journal_entry_id").asInt();
		assertThat(lines(entry)).containsExactlyInAnyOrder(s.inventory() + " dr 40.2 40.2", grni + " cr 40.2 40.2");
		assertThat(read("/api/journal-entries/" + entry).get("currency_code").asString())
				.isEqualTo(jdbc.sql("SELECT base_currency FROM gl_settings").query(String.class).single());

		r = action("/api/purchase-orders/" + po + "/bill", "{\"bill_number\":\"" + uniq("B") + "\",\"bill_date\":\"" + y + "-03-20\"}");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		JsonNode bill = read("/api/purchase-bills/" + json(r.body()).get("bill_id").asInt());
		assertThat(bill.get("reference").asString()).isEqualTo(number);
		assertThat(bill.get("currency_code").asString()).isEqualTo("JPY");
		assertThat(dec(bill.get("total"))).isEqualTo("6000");
		assertThat(read("/api/purchase-orders/" + po).get("billed_status").asString()).isEqualTo("billed");
	}

	@Test
	void openOrdersCancelOnlyWhileUnfulfilled() throws Exception {
		int customer = customer(account("asset"));
		String order = "{\"order_number\":\"%s\",\"customer_id\":" + customer + ",\"order_date\":\"" + y
				+ "-02-01\",\"currency_code\":\"USD\",\"lines\":[{\"description\":\"x\",\"unit_price\":\"1\"}]}";
		int open = create("/api/sales-orders", order.formatted(uniq("SO")));
		assertThat(action("/api/sales-orders/" + open + "/confirm", "").statusCode()).isEqualTo(204);
		assertThat(action("/api/sales-orders/" + open + "/cancel", "").statusCode()).isEqualTo(204);
		assertThat(read("/api/sales-orders/" + open).get("status").asString()).isEqualTo("cancelled");
		assertJsonError(action("/api/sales-orders/" + open + "/cancel", ""), 409);
		assertJsonError(action("/api/sales-orders/" + open + "/confirm", ""), 409);

		int invoiced = create("/api/sales-orders", order.formatted(uniq("SO")));
		action("/api/sales-orders/" + invoiced + "/confirm", "");
		action("/api/sales-orders/" + invoiced + "/invoice", "{\"invoice_number\":\"" + uniq("I") + "\",\"invoice_date\":\"" + y + "-02-02\"}");
		assertJsonError(action("/api/sales-orders/" + invoiced + "/cancel", ""), 409);

		int draft = create("/api/sales-orders", order.formatted(uniq("SO")));
		assertThat(sendJson("DELETE", "/api/sales-orders/" + draft, "", session).statusCode()).isEqualTo(204);
		assertJsonError(get("/api/sales-orders/" + draft, session), 404);
	}

	@Test
	void stockMovementsPostReceiptsAndIssues() throws Exception {
		Stocked s = stocked();
		int wh = warehouse();
		int grni = jdbc.sql("SELECT id FROM accounts WHERE code = '2150'").query(Integer.class).single();
		int untracked = create("/api/products", "{\"sku\":\"" + uniq("SKU") + "\",\"name\":\"Service\"}");
		String d = y + "-02-01";

		assertJsonError(postJson("/api/stock-movements", "{\"warehouse_id\":" + wh + ",\"movement_type\":\"receipt\",\"quantity\":\"1\"}", session), 400);
		assertJsonError(postJson("/api/stock-movements", "{\"product_id\":" + s.product() + ",\"warehouse_id\":" + wh + ",\"movement_type\":\"receipt\"}", session), 400);
		assertJsonError(postJson("/api/stock-movements", movement(s.product(), wh, "receipt", d, "-1", "5"), session), 422);
		assertJsonError(postJson("/api/stock-movements", movement(s.product(), wh, "adjustment", d, "0", "5"), session), 422);
		assertJsonError(postJson("/api/stock-movements", movement(s.product(), wh, "teleport", d, "1", "5"), session), 422);
		assertJsonError(postJson("/api/stock-movements", movement(s.product(), wh, "receipt", d, "1", "-5"), session), 422);
		assertJsonError(postJson("/api/stock-movements", movement(untracked, wh, "receipt", d, "1", "5"), session), 422);

		int receipt = create("/api/stock-movements", movement(s.product(), wh, "receipt", d, "10", "5"));
		JsonNode m = read("/api/stock-movements/" + receipt);
		assertThat(m.get("status").asString()).isEqualTo("draft");
		assertThat(dec(m.get("total_cost"))).isEqualTo("50");
		assertThat(m.get("source_type").isNull()).isTrue();
		int issue = create("/api/stock-movements", movement(s.product(), wh, "issue", d, "-3", "5"));
		assertThat(sendJson("PUT", "/api/stock-movements/" + receipt, movement(s.product(), wh, "receipt", d, "12", "5"), session)
				.statusCode()).isEqualTo(204);

		JsonNode valuation = null;
		for (JsonNode v : read("/api/inventory-valuation")) {
			if (v.get("product_id").asInt() == s.product()) {
				valuation = v;
			}
		}
		assertThat(dec(valuation.get("qty_on_hand"))).isEqualTo("9");
		assertThat(dec(valuation.get("value_on_hand"))).isEqualTo("45");
		assertThat(dec(valuation.get("avg_unit_cost"))).isEqualTo("5");

		assertJsonError(postJson("/api/stock-movements/" + receipt + "/post", "", session), 422);
		int summary = create("/api/accounts", "{\"code\":\"" + uniq("S") + "\",\"name\":\"Header\",\"account_type\":\"liability\"}");
		assertJsonError(postJson("/api/stock-movements/" + receipt + "/post", "{\"credit_account_id\":" + summary + "}", session), 422);
		HttpResponse<String> r = postJson("/api/stock-movements/" + receipt + "/post", "{\"credit_account_id\":" + grni + "}", session);
		int entry = json(r.body()).get("journal_entry_id").asInt();
		assertThat(lines(entry)).containsExactlyInAnyOrder(s.inventory() + " dr 60 60", grni + " cr 60 60");
		assertThat(read("/api/stock-movements/" + receipt).get("status").asString()).isEqualTo("posted");
		assertJsonError(postJson("/api/stock-movements/" + receipt + "/post", "{\"credit_account_id\":" + grni + "}", session), 409);
		assertJsonError(sendJson("DELETE", "/api/stock-movements/" + receipt, "", session), 409);
		assertThat(lines(post("stock-movements", issue))).containsExactlyInAnyOrder(s.cogs() + " dr 15 15", s.inventory() + " cr 15 15");

		int adjustment = create("/api/stock-movements", movement(s.product(), wh, "adjustment", d, "1", "5"));
		assertJsonError(postJson("/api/stock-movements/" + adjustment + "/post", "", session), 422);
		int free = create("/api/stock-movements", movement(s.product(), wh, "issue", d, "-1", "0"));
		assertJsonError(postJson("/api/stock-movements/" + free + "/post", "", session), 422);

		assertJsonError(postJson("/api/stock-movements/" + receipt + "/unpost", "", session), 403);
		r = postJson("/api/stock-movements/" + receipt + "/unpost", "", adminSession);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		assertThat(lines(json(r.body()).get("reversal_entry_id").asInt()))
				.containsExactlyInAnyOrder(s.inventory() + " cr 60 60", grni + " dr 60 60");
		assertThat(dec(read("/api/stock-movements/" + receipt).get("quantity"))).isEqualTo("12");
		// An unposted movement is a 409 here, not a 404 (a NULL entry is not a missing row).
		assertJsonError(postJson("/api/stock-movements/" + receipt + "/unpost", "", adminSession), 409);
		assertThat(sendJson("DELETE", "/api/stock-movements/" + receipt, "", session).statusCode()).isEqualTo(204);
		assertJsonError(postJson("/api/stock-movements/999999/post", "", session), 404);
	}
}
