package com.belunaro.tadmor.service;

/**
 * The documents that settle invoices and bills by application (spec/domain.md
 * §5): payments and credit notes, each with its own applications table. As
 * with DocumentKind, the fields are SQL identifiers from the shared schema.
 */
public enum SettlerKind {

	CUSTOMER_PAYMENT("Customer payment", "customer_payments", "amount", "payment_date", "payment_applications",
			"payment_id", DocumentKind.SALES_INVOICE, "invoice_amount_settled", true),

	SUPPLIER_PAYMENT("Supplier payment", "supplier_payments", "amount", "payment_date", "bill_applications",
			"payment_id", DocumentKind.PURCHASE_BILL, "bill_amount_settled", false),

	SALES_CREDIT_NOTE("Sales credit note", "sales_credit_notes", "total", "credit_note_date",
			"sales_credit_applications", "credit_note_id", DocumentKind.SALES_INVOICE, "invoice_amount_settled", true),

	PURCHASE_CREDIT_NOTE("Purchase credit note", "purchase_credit_notes", "total", "credit_note_date",
			"purchase_credit_applications", "credit_note_id", DocumentKind.PURCHASE_BILL, "bill_amount_settled", false);

	final String label;
	final String table;
	/** The column holding what the settler can apply: a payment's amount, a credit note's total. */
	final String amount;
	final String date;
	final String applications;
	/** The applications' column naming the settler. */
	final String settlerColumn;
	/** What it settles: invoices or bills, whose party, currency, and control account it shares. */
	final DocumentKind target;
	/** The schema's function giving a document's amount settled by payments and credit notes together. */
	final String settled;
	/** Customer side (A/R) or supplier side (A/P), which decides the sides of a realized-FX entry. */
	final boolean receivable;

	SettlerKind(String label, String table, String amount, String date, String applications, String settlerColumn,
			DocumentKind target, String settled, boolean receivable) {
		this.label = label;
		this.table = table;
		this.amount = amount;
		this.date = date;
		this.applications = applications;
		this.settlerColumn = settlerColumn;
		this.target = target;
		this.settled = settled;
		this.receivable = receivable;
	}

	/** The applications' column naming the invoice or bill. */
	String targetColumn() {
		return target.lineDocument;
	}
}
