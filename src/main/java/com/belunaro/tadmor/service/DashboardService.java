package com.belunaro.tadmor.service;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The home screen's figures (spec/domain.md §13.2), over posted documents, against today (UTC). */
@Service
public class DashboardService {

	private final JdbcClient jdbc;

	public DashboardService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** What is outstanding in one currency, and how much of it is overdue. */
	public record Outstanding(String currencyCode, String outstanding, String overdue) {
	}

	/** An invoice or bill with a balance. */
	public record Due(int id, String number, String partyName, String dueDate, String currencyCode, String balance) {
	}

	public record Counts(int openSalesOrders, int openPurchaseOrders, int draftInvoices, int draftBills) {
	}

	/** H1: receivables (sales) or payables, per currency, with the overdue portion. */
	public List<Outstanding> outstanding(boolean receivables) {
		return jdbc.sql("""
				SELECT currency_code, sum(balance)::numeric(19,4)::text AS outstanding,
				    COALESCE(sum(balance) FILTER (WHERE due_date < current_date), 0)::numeric(19,4)::text AS overdue
				FROM %s WHERE status = 'posted' AND balance > 0
				GROUP BY currency_code ORDER BY currency_code""".formatted(receivables ? "sales_invoice_balances"
				: "purchase_bill_balances")).query(Outstanding.class).list();
	}

	/** H2. */
	public Counts counts() {
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM sales_orders WHERE status = 'open') AS open_sales_orders,
				    (SELECT count(*) FROM purchase_orders WHERE status = 'open') AS open_purchase_orders,
				    (SELECT count(*) FROM sales_invoices WHERE status = 'draft') AS draft_invoices,
				    (SELECT count(*) FROM purchase_bills WHERE status = 'draft') AS draft_bills""").query(Counts.class).single();
	}

	/** H3: the most overdue invoices, oldest due date first. */
	public List<Due> overdueInvoices(int limit) {
		return jdbc.sql("""
				SELECT b.invoice_id AS id, b.invoice_number AS number, o.name AS party_name, b.due_date::text AS due_date,
				    b.currency_code, b.balance::numeric(19,4)::text AS balance
				FROM sales_invoice_balances b
				JOIN customers c ON c.id = b.customer_id JOIN organizations o ON o.id = c.organization_id
				WHERE b.status = 'posted' AND b.balance > 0 AND b.due_date < current_date
				ORDER BY b.due_date, b.invoice_id LIMIT ?""").param(limit).query(Due.class).list();
	}

	/** H4: bills due from today through the next 14 days, soonest first. */
	public List<Due> billsDueSoon() {
		return jdbc.sql("""
				SELECT b.bill_id AS id, b.bill_number AS number, o.name AS party_name, b.due_date::text AS due_date,
				    b.currency_code, b.balance::numeric(19,4)::text AS balance
				FROM purchase_bill_balances b
				JOIN suppliers s ON s.id = b.supplier_id JOIN organizations o ON o.id = s.organization_id
				WHERE b.status = 'posted' AND b.balance > 0 AND b.due_date BETWEEN current_date AND current_date + 14
				ORDER BY b.due_date, b.bill_id""").query(Due.class).list();
	}
}
