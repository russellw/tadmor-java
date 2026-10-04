package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.decimal;
import static com.belunaro.tadmor.service.Inputs.flag;
import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;
import static com.belunaro.tadmor.service.ServiceException.found;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Products, the chart of accounts, tax codes, payment terms, and warehouses
 * (spec/api.md §5.4 to §5.6). Master data is never deleted, only deactivated
 * through update (§1.3). As with parties, the schema enforces most rules:
 * unique codes and skus (409), known references, account types, and cash-flow
 * activities, no negative rates or due days, no cash flag on a non-asset, and
 * no self-parenting account (422).
 */
@Service
public class CatalogService {

	private final JdbcClient jdbc;

	public CatalogService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- products ----

	public record Product(int id, String sku, String name, String description, String unitPrice,
			String currencyCode, Integer revenueAccountId, String taxCode, boolean trackInventory,
			Integer inventoryAccountId, Integer cogsAccountId, boolean isActive) {
	}

	public record ProductInput(String sku, String name, String description, String unitPrice, String currencyCode,
			Integer revenueAccountId, String taxCode, Boolean trackInventory, Integer inventoryAccountId,
			Integer cogsAccountId, Boolean isActive) {
		public ProductInput {
			sku = trim(sku);
			name = trim(name);
			unitPrice = decimal(unitPrice, "0");
			trackInventory = flag(trackInventory);
			isActive = flag(isActive);
		}
	}

	private static final String PRODUCT = """
			SELECT id, sku, name, description, unit_price::text AS unit_price, currency_code, revenue_account_id,
			    tax_code, track_inventory, inventory_account_id, cogs_account_id, is_active
			FROM products""";

	public List<Product> products() {
		return jdbc.sql(PRODUCT + " ORDER BY sku").query(Product.class).list();
	}

	public Product product(int id) {
		return jdbc.sql(PRODUCT + " WHERE id = ?").param(id).query(Product.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createProduct(ProductInput in) {
		requireProduct(in);
		return jdbc.sql("""
				INSERT INTO products (sku, name, description, unit_price, currency_code, revenue_account_id,
				    tax_code, track_inventory, inventory_account_id, cogs_account_id)
				VALUES (:sku, :name, :description, CAST(:unitPrice AS numeric), :currencyCode, :revenueAccountId,
				    :taxCode, :trackInventory, :inventoryAccountId, :cogsAccountId)
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	public void updateProduct(int id, ProductInput in) {
		requireProduct(in);
		found(jdbc.sql("""
				UPDATE products SET sku = :sku, name = :name, description = :description,
				    unit_price = CAST(:unitPrice AS numeric), currency_code = :currencyCode,
				    revenue_account_id = :revenueAccountId, tax_code = :taxCode, track_inventory = :trackInventory,
				    inventory_account_id = :inventoryAccountId, cogs_account_id = :cogsAccountId, is_active = :isActive
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	private static void requireProduct(ProductInput in) {
		require(in.sku(), "sku");
		require(in.name(), "name");
	}

	// ---- chart of accounts ----

	public record Account(int id, String code, String name, String accountType, Integer parentId,
			String currencyCode, boolean isPostable, boolean isActive, boolean isCash, String cashFlowActivity) {
	}

	/**
	 * is_postable defaults to false, which makes a summary account, and
	 * cash_flow_activity to operating.
	 */
	public record AccountInput(String code, String name, String accountType, Integer parentId, String currencyCode,
			Boolean isPostable, Boolean isActive, Boolean isCash, String cashFlowActivity) {
		public AccountInput {
			code = trim(code);
			name = trim(name);
			accountType = trim(accountType);
			isPostable = flag(isPostable);
			isActive = flag(isActive);
			isCash = flag(isCash);
			cashFlowActivity = cashFlowActivity == null || cashFlowActivity.isBlank() ? "operating"
					: cashFlowActivity.strip();
		}
	}

	private static final String ACCOUNT = """
			SELECT id, code, name, account_type, parent_id, currency_code, is_postable, is_active, is_cash,
			    cash_flow_activity
			FROM accounts""";

	public List<Account> accounts() {
		return jdbc.sql(ACCOUNT + " ORDER BY code").query(Account.class).list();
	}

	public Account account(int id) {
		return jdbc.sql(ACCOUNT + " WHERE id = ?").param(id).query(Account.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createAccount(AccountInput in) {
		requireAccount(in);
		return jdbc.sql("""
				INSERT INTO accounts (code, name, account_type, parent_id, currency_code, is_postable, is_cash,
				    cash_flow_activity)
				VALUES (:code, :name, :accountType, :parentId, :currencyCode, :isPostable, :isCash, :cashFlowActivity)
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	public void updateAccount(int id, AccountInput in) {
		requireAccount(in);
		found(jdbc.sql("""
				UPDATE accounts SET code = :code, name = :name, account_type = :accountType, parent_id = :parentId,
				    currency_code = :currencyCode, is_postable = :isPostable, is_active = :isActive, is_cash = :isCash,
				    cash_flow_activity = :cashFlowActivity
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	private static void requireAccount(AccountInput in) {
		require(in.code(), "code");
		require(in.name(), "name");
		require(in.accountType(), "account_type");
	}

	// ---- tax codes (keyed by code) ----

	public record TaxCode(String code, String name, String rate, Integer taxAccountId, boolean isActive) {
	}

	public record TaxCodeInput(String code, String name, String rate, Integer taxAccountId, Boolean isActive) {
		public TaxCodeInput {
			code = trim(code);
			name = trim(name);
			rate = decimal(rate, "0");
			isActive = flag(isActive);
		}

		TaxCodeInput withCode(String code) {
			return new TaxCodeInput(code, name, rate, taxAccountId, isActive);
		}
	}

	private static final String TAX_CODE = "SELECT code, name, rate::text AS rate, tax_account_id, is_active FROM tax_codes";

	public List<TaxCode> taxCodes() {
		return jdbc.sql(TAX_CODE + " ORDER BY code").query(TaxCode.class).list();
	}

	public TaxCode taxCode(String code) {
		return jdbc.sql(TAX_CODE + " WHERE code = ?").param(code).query(TaxCode.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	/** Creates the tax code and returns its key as stored. */
	public String createTaxCode(TaxCodeInput in) {
		require(in.code(), "code");
		require(in.name(), "name");
		jdbc.sql("""
				INSERT INTO tax_codes (code, name, rate, tax_account_id)
				VALUES (:code, :name, CAST(:rate AS numeric), :taxAccountId)""").paramSource(in).update();
		return in.code();
	}

	/** The path's code wins over the body's. */
	public void updateTaxCode(String code, TaxCodeInput body) {
		TaxCodeInput in = body.withCode(code);
		require(in.name(), "name");
		found(jdbc.sql("""
				UPDATE tax_codes SET name = :name, rate = CAST(:rate AS numeric), tax_account_id = :taxAccountId,
				    is_active = :isActive
				WHERE code = :code""").paramSource(in).update());
	}

	// ---- payment terms (keyed by code) ----

	public record PaymentTerm(String code, String name, int dueDays) {
	}

	public record PaymentTermInput(String code, String name, Integer dueDays) {
		public PaymentTermInput {
			code = trim(code);
			name = trim(name);
			dueDays = dueDays == null ? 0 : dueDays;
		}

		PaymentTermInput withCode(String code) {
			return new PaymentTermInput(code, name, dueDays);
		}
	}

	private static final String PAYMENT_TERM = "SELECT code, name, due_days FROM payment_terms";

	public List<PaymentTerm> paymentTerms() {
		return jdbc.sql(PAYMENT_TERM + " ORDER BY due_days, code").query(PaymentTerm.class).list();
	}

	public PaymentTerm paymentTerm(String code) {
		return jdbc.sql(PAYMENT_TERM + " WHERE code = ?").param(code).query(PaymentTerm.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	/** Creates the payment term and returns its key as stored. */
	public String createPaymentTerm(PaymentTermInput in) {
		require(in.code(), "code");
		require(in.name(), "name");
		jdbc.sql("INSERT INTO payment_terms (code, name, due_days) VALUES (:code, :name, :dueDays)")
				.paramSource(in).update();
		return in.code();
	}

	/** The path's code wins over the body's. */
	public void updatePaymentTerm(String code, PaymentTermInput body) {
		PaymentTermInput in = body.withCode(code);
		require(in.name(), "name");
		found(jdbc.sql("UPDATE payment_terms SET name = :name, due_days = :dueDays WHERE code = :code")
				.paramSource(in).update());
	}

	// ---- warehouses ----

	public record Warehouse(int id, String code, String name, Integer addressId, boolean isActive) {
	}

	public record WarehouseInput(String code, String name, Integer addressId, Boolean isActive) {
		public WarehouseInput {
			code = trim(code);
			name = trim(name);
			isActive = flag(isActive);
		}
	}

	private static final String WAREHOUSE = "SELECT id, code, name, address_id, is_active FROM warehouses";

	public List<Warehouse> warehouses() {
		return jdbc.sql(WAREHOUSE + " ORDER BY code").query(Warehouse.class).list();
	}

	public Warehouse warehouse(int id) {
		return jdbc.sql(WAREHOUSE + " WHERE id = ?").param(id).query(Warehouse.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createWarehouse(WarehouseInput in) {
		requireWarehouse(in);
		return jdbc.sql("INSERT INTO warehouses (code, name, address_id) VALUES (:code, :name, :addressId) RETURNING id")
				.paramSource(in).query(Integer.class).single();
	}

	public void updateWarehouse(int id, WarehouseInput in) {
		requireWarehouse(in);
		found(jdbc.sql("""
				UPDATE warehouses SET code = :code, name = :name, address_id = :addressId, is_active = :isActive
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	private static void requireWarehouse(WarehouseInput in) {
		require(in.code(), "code");
		require(in.name(), "name");
	}
}
