package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.flag;
import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;
import static com.belunaro.tadmor.service.ServiceException.found;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Organizations, and the customer and supplier roles on them (spec/api.md
 * §5.2, §5.3). The schema enforces most of the rules: at most one own
 * organization and one role of each kind per organization (unique indexes,
 * 409), and known countries, currencies, accounts, terms, and tax codes
 * (foreign keys, 422).
 */
@Service
public class PartyService {

	private final JdbcClient jdbc;

	public PartyService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- organizations ----

	public record Organization(int id, String name, String legalName, String taxId, String countryCode,
			String defaultCurrency, String email, boolean isSelf) {
	}

	public record OrganizationInput(String name, String legalName, String taxId, String countryCode,
			String defaultCurrency, String email, Boolean isSelf) {
		public OrganizationInput {
			name = trim(name);
			isSelf = flag(isSelf);
		}
	}

	private static final String ORGANIZATION = """
			SELECT id, name, legal_name, tax_id, country_code, default_currency, email, is_self
			FROM organizations""";

	public List<Organization> organizations() {
		return jdbc.sql(ORGANIZATION + " ORDER BY name").query(Organization.class).list();
	}

	public Organization organization(int id) {
		return jdbc.sql(ORGANIZATION + " WHERE id = ?").param(id).query(Organization.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createOrganization(OrganizationInput in) {
		require(in.name(), "name");
		return jdbc.sql("""
				INSERT INTO organizations (name, legal_name, tax_id, country_code, default_currency, email, is_self)
				VALUES (:name, :legalName, :taxId, :countryCode, :defaultCurrency, :email, :isSelf)
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	public void updateOrganization(int id, OrganizationInput in) {
		require(in.name(), "name");
		found(jdbc.sql("""
				UPDATE organizations SET name = :name, legal_name = :legalName, tax_id = :taxId,
				    country_code = :countryCode, default_currency = :defaultCurrency, email = :email, is_self = :isSelf
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	// ---- customers ----

	public record Customer(int id, int organizationId, String customerNumber, Integer arAccountId,
			String paymentTermsCode, String currencyCode, String taxCode, String creditLimit, boolean isActive) {
	}

	/** On create, is_active is ignored: customers start active. */
	public record CustomerInput(Integer organizationId, String customerNumber, Integer arAccountId,
			String paymentTermsCode, String currencyCode, String taxCode, String creditLimit, Boolean isActive) {
		public CustomerInput {
			isActive = flag(isActive);
		}
	}

	private static final String CUSTOMER = """
			SELECT id, organization_id, customer_number, ar_account_id, payment_terms_code, currency_code,
			    tax_code, credit_limit::text AS credit_limit, is_active
			FROM customers""";

	public List<Customer> customers() {
		return jdbc.sql(CUSTOMER + " ORDER BY id").query(Customer.class).list();
	}

	public Customer customer(int id) {
		return jdbc.sql(CUSTOMER + " WHERE id = ?").param(id).query(Customer.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createCustomer(CustomerInput in) {
		requireOrganization(in.organizationId());
		return jdbc.sql("""
				INSERT INTO customers (organization_id, customer_number, ar_account_id, payment_terms_code,
				    currency_code, tax_code, credit_limit)
				VALUES (:organizationId, :customerNumber, :arAccountId, :paymentTermsCode,
				    :currencyCode, :taxCode, CAST(:creditLimit AS numeric))
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	public void updateCustomer(int id, CustomerInput in) {
		requireOrganization(in.organizationId());
		found(jdbc.sql("""
				UPDATE customers SET organization_id = :organizationId, customer_number = :customerNumber,
				    ar_account_id = :arAccountId, payment_terms_code = :paymentTermsCode, currency_code = :currencyCode,
				    tax_code = :taxCode, credit_limit = CAST(:creditLimit AS numeric), is_active = :isActive
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	// ---- suppliers ----

	public record Supplier(int id, int organizationId, String supplierNumber, Integer apAccountId,
			String paymentTermsCode, String currencyCode, String taxCode, boolean isActive) {
	}

	/** On create, is_active is ignored: suppliers start active. */
	public record SupplierInput(Integer organizationId, String supplierNumber, Integer apAccountId,
			String paymentTermsCode, String currencyCode, String taxCode, Boolean isActive) {
		public SupplierInput {
			isActive = flag(isActive);
		}
	}

	private static final String SUPPLIER = """
			SELECT id, organization_id, supplier_number, ap_account_id, payment_terms_code, currency_code,
			    tax_code, is_active
			FROM suppliers""";

	public List<Supplier> suppliers() {
		return jdbc.sql(SUPPLIER + " ORDER BY id").query(Supplier.class).list();
	}

	public Supplier supplier(int id) {
		return jdbc.sql(SUPPLIER + " WHERE id = ?").param(id).query(Supplier.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createSupplier(SupplierInput in) {
		requireOrganization(in.organizationId());
		return jdbc.sql("""
				INSERT INTO suppliers (organization_id, supplier_number, ap_account_id, payment_terms_code,
				    currency_code, tax_code)
				VALUES (:organizationId, :supplierNumber, :apAccountId, :paymentTermsCode, :currencyCode, :taxCode)
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	public void updateSupplier(int id, SupplierInput in) {
		requireOrganization(in.organizationId());
		found(jdbc.sql("""
				UPDATE suppliers SET organization_id = :organizationId, supplier_number = :supplierNumber,
				    ap_account_id = :apAccountId, payment_terms_code = :paymentTermsCode, currency_code = :currencyCode,
				    tax_code = :taxCode, is_active = :isActive
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	private static void requireOrganization(Integer organizationId) {
		if (organizationId == null || organizationId <= 0) {
			throw ServiceException.badRequest("organization_id is required");
		}
	}
}
