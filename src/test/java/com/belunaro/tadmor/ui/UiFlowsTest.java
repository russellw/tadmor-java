package com.belunaro.tadmor.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Walks the UI checklist of spec/domain.md §13 through the screens, as a user would. */
class UiFlowsTest extends UiTest {

	/** Master data through the generic forms; returns the new customer's id. */
	private int customer(String name) throws Exception {
		user.submit("/organizations/new", "name", name, "email", "ap@" + System.nanoTime() + ".example");
		int org = jdbc.sql("SELECT id FROM organizations WHERE name = ?").param(name).query(Integer.class).single();
		user.page("/customers/new");
		String ar = option(user.last.body(), "ar_account_id", "1100");
		user.submit("/customers/new", "organization_id", Integer.toString(org), "ar_account_id", ar, "currency_code", "USD");
		String list = user.page("/customers");
		Matcher m = Pattern.compile("href=\"/customers/(\\d+)\">" + Pattern.quote(name) + "<").matcher(list);
		assertThat(m.find()).as("customer listed by organization name (M2)").isTrue();
		return Integer.parseInt(m.group(1));
	}

	@Test
	void everyNavigationLinkOpens() throws Exception {
		String home = user.page("/");
		Matcher links = Pattern.compile("<a href=\"(/[^\"]*)\"[^>]*>[^<]+</a>").matcher(
				home.substring(home.indexOf("<nav"), home.indexOf("</nav>")));
		int count = 0;
		while (links.find()) {
			user.page(links.group(1));
			count++;
		}
		assertThat(count).as("G3: every screen reachable from the sidebar").isGreaterThan(25);
		assertThat(home).contains("New invoice", "Receivables outstanding", "AR aging", "AP aging");
	}

	@Test
	void administratorOnlyThingsAreHiddenAndRefused() throws Exception {
		assertThat(user.page("/")).doesNotContain("href=\"/users\"");
		assertThat(admin.page("/")).contains("href=\"/users\"");
		assertThat(user.get("/users").statusCode()).isEqualTo(403);
		assertThat(user.page("/settings")).contains("Only an administrator can change these.");
		assertThat(user.post("/settings", "base_currency", "USD").statusCode()).isEqualTo(403);
		assertThat(user.post("/sales-invoices/1/unpost").statusCode()).isEqualTo(403);
	}

	@Test
	void unknownAddressesAndRecordsAreNotFound() throws Exception {
		assertThat(user.get("/no-such-screen").statusCode()).isEqualTo(404);
		assertThat(user.get("/sales-invoices/999999").statusCode()).isEqualTo(404);
		assertThat(user.last.body()).contains("Not found");
	}

	@Test
	void masterDataFormsShowRefusals() throws Exception {
		HttpResponse<String> r = user.post("/organizations/new", "name", "");
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).as("G5: the refusal next to the form").contains("class=\"error\"", "name is required");

		user.page("/accounts/new");
		String code = uniq("A");
		user.submit("/accounts/new", "code", code, "name", "Header", "account_type", "asset");
		int id = jdbc.sql("SELECT id FROM accounts WHERE code = ?").param(code).query(Integer.class).single();
		assertThat(user.page("/accounts/" + id)).as("M4: the parent picker never offers the account itself")
				.doesNotContain("<option value=\"" + id + "\"");
		r = user.post("/accounts/" + id, "code", code, "name", "Header", "account_type", "liability", "is_cash", "true");
		assertThat(r.statusCode()).isEqualTo(422);
	}

	@Test
	void invoiceFromFormToPostedAndUnposted() throws Exception {
		int customer = customer("UI Customer " + System.nanoTime());
		String form = user.page("/sales-invoices/new");
		assertThat(form).contains("id=\"client-data\"", "id=\"line-template\"");
		String revenue = option(form, "l_account_id", "4000");

		String number = uniq("INV");
		String detail = user.submit("/sales-invoices/new", "invoice_number", number, "customer_id", Integer.toString(customer),
				"invoice_date", y + "-03-15", "due_date", y + "-04-14", "currency_code", "USD",
				"l_product_id", "", "l_description", "Consulting", "l_quantity", "2", "l_price", "10.005", "l_account_id", revenue,
				"l_tax_code", "", "l_tax_rate", "0",
				"l_product_id", "", "l_description", "", "l_quantity", "1", "l_price", "", "l_account_id", "", "l_tax_code", "",
				"l_tax_rate", "0");
		int id = idOf(detail);
		String page = user.page(detail);
		assertThat(page).contains(number, "USD 20.01", "draft", "Post", "Edit", "data-confirm=\"Delete this invoice?\"",
				"/api/sales-invoices/" + id + "/pdf");
		assertThat(page).as("an unprivileged user is not offered unpost").doesNotContain(">Unpost<");

		// A refused save keeps what was typed and says why.
		HttpResponse<String> refused = user.post("/sales-invoices/" + id + "/edit", "invoice_number", number,
				"customer_id", Integer.toString(customer), "invoice_date", y + "-03-15", "due_date", y + "-03-01",
				"currency_code", "USD", "l_description", "Consulting", "l_quantity", "2", "l_price", "10.005");
		assertThat(refused.statusCode()).isEqualTo(422);
		assertThat(refused.body()).contains("class=\"error\"", "value=\"10.005\"");

		user.submit("/sales-invoices/" + id + "/post");
		page = user.page(detail);
		assertThat(page).contains("posted", "unpaid", "/journal-entries/").doesNotContain(">Post<");
		Matcher entry = Pattern.compile("/journal-entries/(\\d+)").matcher(page);
		assertThat(entry.find()).isTrue();
		assertThat(user.page("/journal-entries/" + entry.group(1))).contains(number, "20.01");

		// Email with nothing configured: the refusal is shown (D7).
		HttpResponse<String> mail = user.post("/sales-invoices/" + id + "/email", "to", "");
		assertThat(mail.statusCode()).isEqualTo(501);
		assertThat(mail.body()).contains("email sending is not configured");

		assertThat(admin.page(detail)).contains(">Unpost<");
		admin.submit("/sales-invoices/" + id + "/unpost");
		assertThat(user.page(detail)).contains("draft");
		assertThat(user.page("/sales-invoices")).contains(number, "USD 20.01");
	}

	@Test
	void paymentsApplyAndCreditNotesListWhatTheySettle() throws Exception {
		int customer = customer("Payer " + System.nanoTime());
		String revenue = option(user.page("/sales-invoices/new"), "l_account_id", "4000");
		int inv = idOf(user.submit("/sales-invoices/new", "invoice_number", uniq("INV"), "customer_id", Integer.toString(customer),
				"invoice_date", y + "-02-01", "currency_code", "USD", "l_description", "Work", "l_quantity", "1",
				"l_price", "100", "l_account_id", revenue));
		user.submit("/sales-invoices/" + inv + "/post");

		String bank = option(user.page("/customer-payments/new"), "deposit_account_id", "1000");
		int pay = idOf(user.submit("/customer-payments/new", "customer_id", Integer.toString(customer), "payment_date",
				y + "-03-01", "currency_code", "USD", "amount", "60", "method", "transfer", "deposit_account_id", bank));
		user.submit("/customer-payments/" + pay + "/post");
		assertThat(user.page("/customer-payments/" + pay)).contains(">Apply<");
		user.submit("/customer-payments/" + pay + "/apply");
		String page = user.page("/customer-payments/" + pay);
		assertThat(page).as("P4: applied to the invoice, with a link").contains("/sales-invoices/" + inv, "USD 60.00");
		assertThat(user.page("/sales-invoices/" + inv)).contains("partial");
	}

	@Test
	void ordersInvoiceAndShip() throws Exception {
		int customer = customer("Orderer " + System.nanoTime());
		String form = user.page("/sales-orders/new");
		String revenue = option(form, "l_account_id", "4000");
		int order = idOf(user.submit("/sales-orders/new", "order_number", uniq("SO"), "customer_id", Integer.toString(customer),
				"order_date", y + "-02-01", "currency_code", "USD", "l_description", "Service", "l_quantity", "4",
				"l_price", "25", "l_account_id", revenue));
		String page = user.page("/sales-orders/" + order);
		assertThat(page).contains("Confirm", "Cancel order").doesNotContain("/sales-orders/" + order + "/invoice\"");
		user.submit("/sales-orders/" + order + "/confirm");

		String fulfil = user.page("/sales-orders/" + order + "/invoice");
		assertThat(fulfil).as("O5: the remaining quantity is filled in").contains("name=\"f_quantity\"", "value=\"4\"");
		Matcher line = Pattern.compile("name=\"f_line_id\" value=\"(\\d+)\"").matcher(fulfil);
		assertThat(line.find()).isTrue();
		HttpResponse<String> zero = user.post("/sales-orders/" + order + "/invoice", "invoice_number", uniq("I"),
				"invoice_date", y + "-02-02", "f_line_id", line.group(1), "f_quantity", "0");
		assertThat(zero.statusCode()).as("lowering everything to zero takes nothing").isEqualTo(422);
		String invoice = user.submit("/sales-orders/" + order + "/invoice", "invoice_number", uniq("I"), "invoice_date",
				y + "-02-02", "f_line_id", line.group(1), "f_quantity", "1");
		assertThat(invoice).startsWith("/sales-invoices/");
		assertThat(user.page(invoice)).as("an order-linked invoice cannot be edited").contains("Produced from an order")
				.doesNotContain(">Edit<");
		page = user.page("/sales-orders/" + order);
		assertThat(page).contains("partial");
		assertThat(page).as("fulfilled in part: no cancel").doesNotContain("Cancel order");
	}

	@Test
	void stockReceiptPostsAgainstTheChosenAccount() throws Exception {
		String sku = uniq("SKU");
		assertThat(user.submit("/products/new", "sku", sku, "name", "Stocked", "track_inventory", "true",
				"inventory_account_id", option(user.page("/products/new"), "inventory_account_id", "1200"))).isEqualTo("/products");
		int product = jdbc.sql("SELECT id FROM products WHERE sku = ?").param(sku).query(Integer.class).single();
		String code = uniq("WH");
		user.submit("/warehouses/new", "code", code, "name", "Main");
		int warehouse = jdbc.sql("SELECT id FROM warehouses WHERE code = ?").param(code).query(Integer.class).single();
		String receipt = user.submit("/stock-movements/new", "product_id", Integer.toString(product), "warehouse_id",
				Integer.toString(warehouse), "movement_type", "receipt", "movement_date", y + "-02-01", "quantity", "5",
				"unit_cost", "3");
		String page = user.page(receipt);
		assertThat(page).contains("name=\"credit_account_id\"", "Credit 2150");
		user.submit(receipt + "/post", "credit_account_id", option(page, "credit_account_id", "2150"));
		assertThat(user.page(receipt)).contains("posted", "/journal-entries/");

		// S2: an issue's magnitude is signed by its type.
		String issue = user.submit("/stock-movements/new", "product_id", Integer.toString(product), "warehouse_id",
				Integer.toString(warehouse), "movement_type", "issue", "movement_date", y + "-02-02", "quantity", "2");
		assertThat(user.page(issue)).contains("<dd>-2</dd>");
	}

	@Test
	void bankStatementImportMatchAndReconcile() throws Exception {
		int customer = customer("Depositor " + System.nanoTime());
		String bank = option(user.page("/bank-statements/new"), "account_id", "1000");
		String bankAccount = option(user.page("/customer-payments/new"), "deposit_account_id", "1000");
		int pay = idOf(user.submit("/customer-payments/new", "customer_id", Integer.toString(customer), "payment_date",
				y + "-03-01", "currency_code", "USD", "amount", "42.5", "deposit_account_id", bankAccount));
		user.submit("/customer-payments/" + pay + "/post");

		String statement = user.submit("/bank-statements/new", "account_id", bank, "statement_date", y + "-03-31",
				"opening_balance", "0", "closing_balance", "42.5");
		int id = idOf(statement);
		HttpResponse<String> imported = user.post(statement + "/import", "csv",
				"date,description,amount\n" + y + "-03-01,\"Deposit, customer\",42.50\n");
		assertThat(imported.statusCode()).isEqualTo(200);
		assertThat(imported.body()).contains("Imported 1 lines.", "Deposit, customer");
		assertThat(user.post(statement + "/import", "csv", y + "-03-02,Bad,zero").statusCode()).isEqualTo(422);

		HttpResponse<String> matched = user.post(statement + "/auto-match");
		assertThat(matched.body()).contains("Matched ");
		user.submit(statement + "/reconcile");
		String page = user.page(statement);
		assertThat(page).contains("reconciled").doesNotContain(">Auto-match<");
		assertThat(admin.page(statement)).contains(">Reopen<");
		assertThat(user.page("/bank-statements")).contains("1 of 1");
		assertThat(id).isPositive();
	}

	@Test
	void periodsToggleAndTheNextPeriodIsProposed() throws Exception {
		String year = user.page("/periods");
		Matcher fy = Pattern.compile("/fiscal-years/(\\d+)/edit[^<]*</a>").matcher(year.substring(year.indexOf("FY-ui-" + y)));
		assertThat(fy.find()).isTrue();
		user.submit("/accounting-periods/new", "fiscal_year_id", fy.group(1), "name", "Jan", "start_date", y + "-01-01",
				"end_date", y + "-01-31");
		String proposal = user.page("/accounting-periods/new");
		assertThat(proposal).contains("value=\"" + y + "-02\"", "value=\"" + y + "-02-01\"", "value=\"" + y + "-02-28\"");

		String periods = user.page("/periods");
		Matcher toggle = Pattern.compile("/accounting-periods/(\\d+)/toggle").matcher(
				periods.substring(periods.indexOf("FY-ui-" + y)));
		assertThat(toggle.find()).isTrue();
		user.submit("/accounting-periods/" + toggle.group(1) + "/toggle");
		assertThat(jdbc.sql("SELECT status FROM accounting_periods WHERE id = ?").param(Integer.parseInt(toggle.group(1)))
				.query(String.class).single()).isEqualTo("closed");

		assertThat(admin.page("/year-end/" + fy.group(1) + "/close")).contains("Retained Earnings", "closing entry");
		assertThat(user.get("/year-end/" + fy.group(1) + "/close").statusCode()).isEqualTo(403);
	}
}
