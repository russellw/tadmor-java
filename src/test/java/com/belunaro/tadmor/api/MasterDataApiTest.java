package com.belunaro.tadmor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.belunaro.tadmor.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * spec/api.md §5.2 to §5.6, with the decimal rules of §1.2 and the write
 * semantics of §1.3. Any signed-in user may maintain master data.
 */
class MasterDataApiTest extends IntegrationTest {

	private String session;

	@BeforeEach
	void signIn() throws Exception {
		session = login(createUser(false));
	}

	private static String uniq(String prefix) {
		return prefix + System.nanoTime();
	}

	private int create(String path, String body) throws Exception {
		HttpResponse<String> r = postJson(path, body, session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		return Integer.parseInt(r.body().replaceAll("\\D", ""));
	}

	private HttpResponse<String> put(String path, String body) throws Exception {
		return sendJson("PUT", path, body, session);
	}

	private String read(String path) throws Exception {
		HttpResponse<String> r = get(path, session);
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		return r.body();
	}

	private int account(String type) throws Exception {
		return create("/api/accounts", "{\"code\":\"" + uniq("A") + "\",\"name\":\"X\",\"account_type\":\"" + type
				+ "\",\"is_postable\":true}");
	}

	@Test
	void organizationsAreFullyReplacedAndAtMostOneIsSelf() throws Exception {
		int id = create("/api/organizations", "{\"name\":\"" + uniq("Org") + "\",\"legal_name\":\"Org Ltd\","
				+ "\"country_code\":\"IE\",\"default_currency\":\"EUR\",\"email\":\"a@b.example\"}");
		assertThat(read("/api/organizations/" + id)).contains("\"country_code\":\"IE\"", "\"is_self\":false");

		assertThat(put("/api/organizations/" + id, "{\"name\":\"Renamed\"}").statusCode()).isEqualTo(204);
		assertThat(read("/api/organizations/" + id)).contains("\"name\":\"Renamed\"", "\"legal_name\":null",
				"\"country_code\":null", "\"default_currency\":null", "\"email\":null");

		assertJsonError(postJson("/api/organizations", "{\"name\":\" \"}", session), 400);
		assertJsonError(postJson("/api/organizations", "{\"name\":\"X\",\"country_code\":\"ZZ\"}", session), 422);
		assertJsonError(put("/api/organizations/999999", "{\"name\":\"X\"}"), 404);

		// At most one own organization, whichever test created it.
		jdbc.sql("UPDATE organizations SET is_self = false").update();
		create("/api/organizations", "{\"name\":\"" + uniq("Self") + "\",\"is_self\":true}");
		assertJsonError(postJson("/api/organizations", "{\"name\":\"Second\",\"is_self\":true}", session), 409);
		assertJsonError(put("/api/organizations/" + id, "{\"name\":\"X\",\"is_self\":true}"), 409);
	}

	@Test
	void customerRolesAndCreditLimit() throws Exception {
		int org = create("/api/organizations", "{\"name\":\"" + uniq("Cust") + "\"}");
		assertJsonError(postJson("/api/customers", "{}", session), 400);
		assertJsonError(postJson("/api/customers", "{\"organization_id\":0}", session), 400);
		assertJsonError(postJson("/api/customers", "{\"organization_id\":999999}", session), 422);
		assertJsonError(postJson("/api/customers", "{\"organization_id\":" + org + ",\"credit_limit\":\"-1\"}", session), 422);

		int id = create("/api/customers", "{\"organization_id\":" + org + ",\"payment_terms_code\":\"NET30\","
				+ "\"credit_limit\":\"5000\",\"is_active\":false}");
		assertThat(read("/api/customers/" + id)).contains("\"credit_limit\":\"5000.0000\"", "\"is_active\":true",
				"\"payment_terms_code\":\"NET30\"");
		assertJsonError(postJson("/api/customers", "{\"organization_id\":" + org + "}", session), 409);

		// The same organization may also be a supplier, once.
		create("/api/suppliers", "{\"organization_id\":" + org + "}");
		assertJsonError(postJson("/api/suppliers", "{\"organization_id\":" + org + "}", session), 409);

		assertThat(put("/api/customers/" + id, "{\"organization_id\":" + org + "}").statusCode()).isEqualTo(204);
		assertThat(read("/api/customers/" + id)).contains("\"credit_limit\":null", "\"is_active\":false");
	}

	@Test
	void decimalsAreRoundedToScaleAndRangeChecked() throws Exception {
		String sku = uniq("SKU");
		int id = create("/api/products", "{\"sku\":\"" + sku + "\",\"name\":\"Widget\"}");
		assertThat(read("/api/products/" + id)).contains("\"unit_price\":\"0.0000\"", "\"track_inventory\":false");

		String body = "{\"sku\":\"" + sku + "\",\"name\":\"Widget\",\"is_active\":true,\"unit_price\":";
		assertThat(put("/api/products/" + id, body + "\"1.00005\"}").statusCode()).isEqualTo(204);
		assertThat(read("/api/products/" + id)).contains("\"unit_price\":\"1.0001\"");
		assertThat(put("/api/products/" + id, body + "\"-2.00005\"}").statusCode()).isEqualTo(204);
		assertThat(read("/api/products/" + id)).contains("\"unit_price\":\"-2.0001\"");

		assertJsonError(put("/api/products/" + id, body + "\"twelve\"}"), 422);
		assertJsonError(put("/api/products/" + id, body + "\"1000000000000000\"}"), 422);
		assertThat(put("/api/products/" + id, body + "\"999999999999999.9999\"}").statusCode()).isEqualTo(204);

		assertJsonError(postJson("/api/products", "{\"sku\":\"" + sku + "\",\"name\":\"Dup\"}", session), 409);
		assertJsonError(postJson("/api/products", "{\"sku\":\"" + uniq("S") + "\",\"name\":\"\"}", session), 400);
		assertJsonError(postJson("/api/products", "{\"sku\":\"" + uniq("S") + "\",\"name\":\"X\",\"revenue_account_id\":999999}", session), 422);
	}

	@Test
	void chartOfAccountsRules() throws Exception {
		assertJsonError(postJson("/api/accounts", "{\"code\":\"" + uniq("A") + "\",\"name\":\"X\"}", session), 400);
		assertJsonError(postJson("/api/accounts", "{\"code\":\"" + uniq("A") + "\",\"name\":\"X\",\"account_type\":\"bogus\"}", session), 422);
		assertJsonError(postJson("/api/accounts", "{\"code\":\"" + uniq("A") + "\",\"name\":\"X\",\"account_type\":\"liability\",\"is_cash\":true}", session), 422);
		assertJsonError(postJson("/api/accounts", "{\"code\":\"" + uniq("A") + "\",\"name\":\"X\",\"account_type\":\"asset\",\"cash_flow_activity\":\"sideways\"}", session), 422);

		String code = uniq("A");
		int id = create("/api/accounts", "{\"code\":\"" + code + "\",\"name\":\"Header\",\"account_type\":\"asset\"}");
		assertThat(read("/api/accounts/" + id)).contains("\"is_postable\":false", "\"cash_flow_activity\":\"operating\"",
				"\"is_active\":true", "\"parent_id\":null");
		assertJsonError(put("/api/accounts/" + id, "{\"code\":\"" + code + "\",\"name\":\"X\",\"account_type\":\"asset\",\"parent_id\":" + id + "}"), 422);
	}

	@Test
	void codeKeyedRecords() throws Exception {
		String tax = uniq("TX");
		HttpResponse<String> r = postJson("/api/tax-codes", "{\"code\":\" " + tax + " \",\"name\":\"VAT\",\"rate\":\"20\",\"tax_account_id\":"
				+ account("liability") + "}", session);
		assertThat(r.statusCode()).isEqualTo(201);
		assertThat(r.body()).isEqualTo("{\"code\":\"" + tax + "\"}");
		assertJsonError(postJson("/api/tax-codes", "{\"code\":\"" + tax + "\",\"name\":\"Dup\"}", session), 409);
		assertJsonError(postJson("/api/tax-codes", "{\"code\":\"" + uniq("TX") + "\",\"name\":\"X\",\"rate\":\"-1\"}", session), 422);

		assertThat(put("/api/tax-codes/" + tax, "{\"code\":\"IGNORED\",\"name\":\"Reduced\",\"rate\":\"13.5\",\"is_active\":true}").statusCode())
				.isEqualTo(204);
		assertThat(read("/api/tax-codes/" + tax)).contains("\"name\":\"Reduced\"", "\"rate\":\"13.5000\"", "\"tax_account_id\":null");
		assertJsonError(get("/api/tax-codes/IGNORED", session), 404);

		String term = uniq("PT");
		assertJsonError(postJson("/api/payment-terms", "{\"code\":\"" + term + "\",\"name\":\"Bad\",\"due_days\":-1}", session), 422);
		assertThat(postJson("/api/payment-terms", "{\"code\":\"" + term + "\",\"name\":\"Net 45\",\"due_days\":45}", session).statusCode())
				.isEqualTo(201);
		assertJsonError(put("/api/payment-terms/" + term, "{\"name\":\"\",\"due_days\":45}"), 400);
		assertJsonError(put("/api/payment-terms/" + uniq("NONE"), "{\"name\":\"X\",\"due_days\":1}"), 404);
		String list = read("/api/payment-terms");
		assertThat(list.indexOf("\"NET30\"")).isLessThan(list.indexOf("\"" + term + "\""));
		assertThat(list.indexOf("\"" + term + "\"")).isLessThan(list.indexOf("\"NET60\""));
	}

	@Test
	void warehousesDeactivateByOmission() throws Exception {
		String code = uniq("WH");
		int id = create("/api/warehouses", "{\"code\":\"" + code + "\",\"name\":\"Main\"}");
		assertThat(read("/api/warehouses/" + id)).contains("\"is_active\":true", "\"address_id\":null");
		assertThat(put("/api/warehouses/" + id, "{\"code\":\"" + code + "\",\"name\":\"Closed\"}").statusCode()).isEqualTo(204);
		assertThat(read("/api/warehouses/" + id)).contains("\"is_active\":false");
		assertJsonError(postJson("/api/warehouses", "{\"code\":\"" + code + "\",\"name\":\"Dup\"}", session), 409);
	}

	@Test
	void accountLedger() throws Exception {
		int id = account("asset");
		assertThat(read("/api/accounts/" + id + "/ledger")).isEqualTo("[]");
		assertThat(read("/api/accounts/" + id + "/ledger?from=2026-01-01&to=2026-12-31")).isEqualTo("[]");
		assertJsonError(get("/api/accounts/" + id + "/ledger?from=yesterday", session), 400);
		assertJsonError(get("/api/accounts/999999/ledger", session), 404);
		assertJsonError(get("/api/accounts/999999/ledger?to=2026-13-45", session), 400);
	}
}
