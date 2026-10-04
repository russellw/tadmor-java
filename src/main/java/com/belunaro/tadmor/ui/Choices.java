package com.belunaro.tadmor.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The options of the UI's pickers (spec/domain.md §13: "a picker over active
 * records for each reference"), as {@code ${@choices.of('accounts')}}.
 * Inactive records are included but flagged, so a form can keep showing a
 * reference that has since been deactivated while offering only active ones.
 */
@Component("choices")
public class Choices {

	public record Option(String value, String label, boolean active) {
	}

	private final JdbcClient jdbc;

	public Choices(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private List<Option> query(String sql) {
		return jdbc.sql(sql).query((rs, n) -> new Option(rs.getString(1), rs.getString(2), rs.getBoolean(3))).list();
	}

	private static List<Option> fixed(String... values) {
		return java.util.Arrays.stream(values).map(v -> new Option(v, v.replace('_', ' '), true)).toList();
	}

	/** The options for a key, as the field definitions name them. */
	public List<Option> of(String key) {
		return switch (key) {
		case "accounts" -> query("SELECT id, code || ' · ' || name, is_active FROM accounts ORDER BY code");
		case "postable-accounts" -> query("SELECT id, code || ' · ' || name, is_active AND is_postable FROM accounts ORDER BY code");
		case "cash-accounts" -> query("SELECT id, code || ' · ' || name, is_active AND is_postable FROM accounts WHERE is_cash ORDER BY code");
		case "equity-accounts" -> query("SELECT id, code || ' · ' || name, is_active AND is_postable FROM accounts "
				+ "WHERE account_type = 'equity' ORDER BY code");
		case "organizations" -> query("SELECT id, name, true FROM organizations ORDER BY name");
		case "customers" -> query("SELECT c.id, o.name || COALESCE(' (' || c.customer_number || ')', ''), c.is_active "
				+ "FROM customers c JOIN organizations o ON o.id = c.organization_id ORDER BY o.name");
		case "suppliers" -> query("SELECT s.id, o.name || COALESCE(' (' || s.supplier_number || ')', ''), s.is_active "
				+ "FROM suppliers s JOIN organizations o ON o.id = s.organization_id ORDER BY o.name");
		case "products" -> query("SELECT id, sku || ' · ' || name, is_active FROM products ORDER BY sku");
		case "stocked-products" -> query("SELECT id, sku || ' · ' || name, is_active FROM products WHERE track_inventory ORDER BY sku");
		case "tax-codes" -> query("SELECT code, code || ' · ' || name || ' (' || trim_scale(rate) || '%)', is_active "
				+ "FROM tax_codes ORDER BY code");
		case "payment-terms" -> query("SELECT code, name, true FROM payment_terms ORDER BY due_days, code");
		case "warehouses" -> query("SELECT id, code || ' · ' || name, is_active FROM warehouses ORDER BY code");
		case "currencies" -> query("SELECT code, code || ' · ' || name, true FROM currencies ORDER BY code");
		case "countries" -> query("SELECT code, name, true FROM countries ORDER BY name");
		case "fiscal-years" -> query("SELECT id, name, status = 'open' FROM fiscal_years ORDER BY start_date");
		case "account-types" -> fixed("asset", "liability", "equity", "revenue", "expense");
		case "activities" -> fixed("operating", "investing", "financing");
		case "methods" -> fixed("cash", "check", "card", "transfer", "other");
		case "movement-types" -> fixed("receipt", "issue", "adjustment", "transfer_in", "transfer_out");
		default -> throw new IllegalArgumentException("no choices named " + key);
		};
	}

	/** Value to label, for showing references in lists. */
	public Map<String, String> labels(String key) {
		Map<String, String> out = new LinkedHashMap<>();
		of(key).forEach(o -> out.put(o.value(), o.label()));
		return out;
	}
}
