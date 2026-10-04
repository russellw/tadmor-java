package com.belunaro.tadmor.ui;

import static com.belunaro.tadmor.ui.Resource.Column.of;
import static com.belunaro.tadmor.ui.Resource.Column.ref;
import static com.belunaro.tadmor.ui.Resource.Column.text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.belunaro.tadmor.security.CurrentUser;
import com.belunaro.tadmor.service.CatalogService;
import com.belunaro.tadmor.service.CatalogService.AccountInput;
import com.belunaro.tadmor.service.CatalogService.PaymentTermInput;
import com.belunaro.tadmor.service.CatalogService.ProductInput;
import com.belunaro.tadmor.service.CatalogService.TaxCodeInput;
import com.belunaro.tadmor.service.CatalogService.WarehouseInput;
import com.belunaro.tadmor.service.PartyService;
import com.belunaro.tadmor.service.PartyService.CustomerInput;
import com.belunaro.tadmor.service.PartyService.OrganizationInput;
import com.belunaro.tadmor.service.PartyService.SupplierInput;
import com.belunaro.tadmor.service.SettingsService;
import com.belunaro.tadmor.service.SettingsService.ExchangeRateInput;
import com.belunaro.tadmor.service.UserService;
import com.belunaro.tadmor.service.UserService.NewUser;
import com.belunaro.tadmor.service.UserService.UserUpdate;
import com.belunaro.tadmor.ui.Resource.Field;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The record types the UI lists and edits generically (spec/domain.md §13.3,
 * and the exchange rates of A3), each with the columns and fields the
 * checklist asks for. Master data has no delete; it is deactivated through
 * its form (M6).
 */
@Component
public class Resources {

	private final Map<String, Resource> byPath = new LinkedHashMap<>();

	public Resources(Ui ui, PartyService parties, CatalogService catalog, UserService users, SettingsService settings) {
		Field active = Field.of("is_active", "Active", "checkbox").mode("edit");

		add(new Resource("organizations", "Organizations", "organization",
				List.of(text("name", "Name"), text("legal_name", "Legal name"), text("tax_id", "Tax id"),
						text("country_code", "Country"), text("default_currency", "Currency")),
				List.of(Field.required("name", "Name", "text"), Field.of("legal_name", "Legal name", "text"),
						Field.of("tax_id", "Tax id", "text"), Field.select("country_code", "Country", "countries"),
						Field.select("default_currency", "Default currency", "currencies"), Field.of("email", "Email", "email"),
						Field.of("is_self", "Our own company", "checkbox")),
				parties::organizations, id -> parties.organization(Integer.parseInt(id)),
				v -> id(parties.createOrganization(ui.bind(v, OrganizationInput.class))),
				(id, v) -> parties.updateOrganization(Integer.parseInt(id), ui.bind(v, OrganizationInput.class)),
				Resources::byId, null, null));

		add(new Resource("customers", "Customers", "customer",
				List.of(ref("organization_id", "Organization", "organizations"), text("customer_number", "Number"),
						text("currency_code", "Currency"), text("tax_code", "Tax code"), text("payment_terms_code", "Terms"),
						of("credit_limit", "Credit limit", "amount"), of("is_active", "Active", "bool")),
				List.of(Field.select("organization_id", "Organization", "organizations").requiredField().mode("fixed"),
						Field.of("customer_number", "Customer number", "text"),
						Field.select("ar_account_id", "A/R account", "postable-accounts"),
						Field.select("payment_terms_code", "Payment terms", "payment-terms"),
						Field.select("currency_code", "Currency", "currencies"), Field.select("tax_code", "Tax code", "tax-codes"),
						Field.of("credit_limit", "Credit limit", "decimal"), active),
				parties::customers, id -> parties.customer(Integer.parseInt(id)),
				v -> id(parties.createCustomer(ui.bind(v, CustomerInput.class))),
				(id, v) -> parties.updateCustomer(Integer.parseInt(id), ui.bind(v, CustomerInput.class)),
				Resources::byId, null, null));

		add(new Resource("suppliers", "Suppliers", "supplier",
				List.of(ref("organization_id", "Organization", "organizations"), text("supplier_number", "Number"),
						text("currency_code", "Currency"), text("tax_code", "Tax code"), text("payment_terms_code", "Terms"),
						of("is_active", "Active", "bool")),
				List.of(Field.select("organization_id", "Organization", "organizations").requiredField().mode("fixed"),
						Field.of("supplier_number", "Supplier number", "text"),
						Field.select("ap_account_id", "A/P account", "postable-accounts"),
						Field.select("payment_terms_code", "Payment terms", "payment-terms"),
						Field.select("currency_code", "Currency", "currencies"), Field.select("tax_code", "Tax code", "tax-codes"),
						active),
				parties::suppliers, id -> parties.supplier(Integer.parseInt(id)),
				v -> id(parties.createSupplier(ui.bind(v, SupplierInput.class))),
				(id, v) -> parties.updateSupplier(Integer.parseInt(id), ui.bind(v, SupplierInput.class)),
				Resources::byId, null, null));

		add(new Resource("products", "Products", "product",
				List.of(text("sku", "SKU"), text("name", "Name"), of("unit_price", "Unit price", "amount"),
						text("currency_code", "Currency"), text("tax_code", "Tax code"), of("track_inventory", "Stocked", "bool"),
						of("is_active", "Active", "bool")),
				List.of(Field.required("sku", "SKU", "text"), Field.required("name", "Name", "text"),
						Field.of("description", "Description", "textarea"), Field.of("unit_price", "Unit price", "decimal"),
						Field.select("currency_code", "Currency", "currencies"), Field.select("tax_code", "Tax code", "tax-codes"),
						Field.select("revenue_account_id", "Revenue account", "postable-accounts"),
						Field.of("track_inventory", "Track inventory", "checkbox"),
						Field.select("inventory_account_id", "Inventory account", "postable-accounts"),
						Field.select("cogs_account_id", "COGS account", "postable-accounts"), active),
				catalog::products, id -> catalog.product(Integer.parseInt(id)),
				v -> id(catalog.createProduct(ui.bind(v, ProductInput.class))),
				(id, v) -> catalog.updateProduct(Integer.parseInt(id), ui.bind(v, ProductInput.class)),
				Resources::byId, null, null));

		add(new Resource("accounts", "Chart of accounts", "account",
				List.of(text("code", "Code"), text("name", "Name"), text("account_type", "Type"), text("currency_code", "Currency"),
						of("is_postable", "Postable", "bool"), of("is_active", "Active", "bool")),
				List.of(Field.required("code", "Code", "text"), Field.required("name", "Name", "text"),
						Field.select("account_type", "Type", "account-types").requiredField(),
						Field.select("parent_id", "Parent", "accounts"), Field.select("currency_code", "Currency", "currencies"),
						Field.of("is_postable", "Postable", "checkbox"), Field.of("is_cash", "Cash or bank", "checkbox"),
						Field.select("cash_flow_activity", "Cash-flow activity", "activities"), active),
				catalog::accounts, id -> catalog.account(Integer.parseInt(id)),
				v -> id(catalog.createAccount(ui.bind(v, AccountInput.class))),
				(id, v) -> catalog.updateAccount(Integer.parseInt(id), ui.bind(v, AccountInput.class)),
				Resources::byId, null, new Resource.RowLink("Ledger", "/ledger")));

		add(new Resource("tax-codes", "Tax codes", "tax code",
				List.of(text("code", "Code"), text("name", "Name"), of("rate", "Rate %", "qty"), of("is_active", "Active", "bool")),
				List.of(Field.required("code", "Code", "text").mode("fixed"), Field.required("name", "Name", "text"),
						Field.of("rate", "Rate (percent)", "decimal"),
						Field.select("tax_account_id", "Tax account", "postable-accounts"), active),
				catalog::taxCodes, catalog::taxCode, v -> catalog.createTaxCode(ui.bind(v, TaxCodeInput.class)),
				(code, v) -> catalog.updateTaxCode(code, ui.bind(v, TaxCodeInput.class)),
				v -> (String) v.get("code"), null, null));

		add(new Resource("payment-terms", "Payment terms", "payment term",
				List.of(text("code", "Code"), text("name", "Name"), text("due_days", "Due days")),
				List.of(Field.required("code", "Code", "text").mode("fixed"), Field.required("name", "Name", "text"),
						Field.of("due_days", "Due days", "integer")),
				catalog::paymentTerms, catalog::paymentTerm, v -> catalog.createPaymentTerm(ui.bind(v, PaymentTermInput.class)),
				(code, v) -> catalog.updatePaymentTerm(code, ui.bind(v, PaymentTermInput.class)),
				v -> (String) v.get("code"), null, null));

		add(new Resource("warehouses", "Warehouses", "warehouse",
				List.of(text("code", "Code"), text("name", "Name"), of("is_active", "Active", "bool")),
				List.of(Field.required("code", "Code", "text"), Field.required("name", "Name", "text"), active),
				catalog::warehouses, id -> catalog.warehouse(Integer.parseInt(id)),
				v -> id(catalog.createWarehouse(ui.bind(v, WarehouseInput.class))),
				(id, v) -> catalog.updateWarehouse(Integer.parseInt(id), ui.bind(v, WarehouseInput.class)),
				Resources::byId, null, null));

		add(new Resource("users", "Users", "user",
				List.of(text("email", "Email"), text("full_name", "Name"), of("is_admin", "Administrator", "bool"),
						of("is_active", "Active", "bool")),
				List.of(Field.required("email", "Email", "email"), Field.required("full_name", "Name", "text"),
						Field.required("password", "Password (at least 8 characters)", "password").mode("create"),
						Field.of("is_admin", "Administrator", "checkbox"), active),
				users::list, id -> users.get(Integer.parseInt(id)), v -> id(users.create(ui.bind(v, NewUser.class))),
				(id, v) -> users.update(caller(), Integer.parseInt(id), ui.bind(v, UserUpdate.class)),
				Resources::byId, null, new Resource.RowLink("Reset password", "/password")));

		add(new Resource("exchange-rates", "Exchange rates", "exchange rate",
				List.of(text("currency_code", "Currency"), text("rate_date", "Date"), of("rate", "Rate", "qty")),
				List.of(Field.select("currency_code", "Currency", "currencies").requiredField().mode("fixed"),
						Field.required("rate_date", "Date", "date").mode("fixed"),
						Field.required("rate", "Rate (base units per unit)", "decimal")),
				settings::exchangeRates,
				key -> settings.exchangeRates().stream().filter(r -> rateKey(r.currencyCode(), r.rateDate()).equals(key))
						.findFirst().orElseThrow(com.belunaro.tadmor.service.ServiceException::notFound),
				v -> {
					ExchangeRateInput created = settings.createExchangeRate(ui.bind(v, ExchangeRateInput.class));
					return rateKey(created.currencyCode(), created.rateDate());
				},
				(key, v) -> settings.updateExchangeRate(key.substring(0, 3), key.substring(4), (String) v.get("rate")),
				v -> rateKey((String) v.get("currency_code"), (String) v.get("rate_date")),
				key -> settings.deleteExchangeRate(key.substring(0, 3), key.substring(4)), null));
	}

	private void add(Resource r) {
		byPath.put(r.path(), r);
	}

	public Optional<Resource> get(String path) {
		return Optional.ofNullable(byPath.get(path));
	}

	private static String id(int id) {
		return Integer.toString(id);
	}

	private static String byId(Map<String, Object> row) {
		return String.valueOf(row.get("id"));
	}

	/** A rate's key in URLs: currency and date, which a path segment cannot hold with a slash. */
	private static String rateKey(String currency, String date) {
		return currency + "_" + date;
	}

	private static CurrentUser caller() {
		return (CurrentUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
	}
}
