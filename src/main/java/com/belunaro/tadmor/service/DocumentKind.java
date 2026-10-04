package com.belunaro.tadmor.service;

/**
 * The documents with lines (spec/api.md §5.9, §5.10), described by the
 * schema names that set them apart, so that one implementation of the draft
 * lifecycle (DocumentService) serves every kind, and one of posting
 * (PostingService) every kind that posts. The fields are SQL identifiers from the shared schema, never user
 * input, which is what makes it safe to build statements from them.
 */
public enum DocumentKind {

	SALES_INVOICE("Sales invoice", "sales_invoices", "sales_invoice_lines", "invoice_id",
			"invoice_number", "customer_id", "invoice_date", "due_date",
			"unit_price", "revenue_account_id", "revenue_account_id",
			"customers", "ar_account_id", Side.CREDIT,
			"sales_invoice_balances", "invoice_id", "payment_status", true,
			"""
					SELECT 1 FROM payment_applications WHERE invoice_id = :id
					UNION ALL SELECT 1 FROM sales_credit_applications WHERE invoice_id = :id"""),

	PURCHASE_BILL("Purchase bill", "purchase_bills", "purchase_bill_lines", "bill_id",
			"bill_number", "supplier_id", "bill_date", "due_date",
			"unit_cost", "expense_account_id", "inventory_account_id",
			"suppliers", "ap_account_id", Side.DEBIT,
			"purchase_bill_balances", "bill_id", "payment_status", true,
			"""
					SELECT 1 FROM bill_applications WHERE bill_id = :id
					UNION ALL SELECT 1 FROM purchase_credit_applications WHERE bill_id = :id"""),

	/** Credits a customer: the mirror of an invoice. Credit notes do not age, so they have no due date. */
	SALES_CREDIT_NOTE("Sales credit note", "sales_credit_notes", "sales_credit_note_lines", "credit_note_id",
			"credit_note_number", "customer_id", "credit_note_date", null,
			"unit_price", "revenue_account_id", "revenue_account_id",
			"customers", "ar_account_id", Side.DEBIT,
			"sales_credit_note_balances", "credit_note_id", "application_status", false,
			"SELECT 1 FROM sales_credit_applications WHERE credit_note_id = :id"),

	/** A supplier's credit to us: the mirror of a bill. */
	PURCHASE_CREDIT_NOTE("Purchase credit note", "purchase_credit_notes", "purchase_credit_note_lines", "credit_note_id",
			"credit_note_number", "supplier_id", "credit_note_date", null,
			"unit_cost", "expense_account_id", "inventory_account_id",
			"suppliers", "ap_account_id", Side.CREDIT,
			"purchase_credit_note_balances", "credit_note_id", "application_status", false,
			"SELECT 1 FROM purchase_credit_applications WHERE credit_note_id = :id"),

	/**
	 * Orders are drafted like invoices and bills, but never post: they are
	 * read, confirmed, and fulfilled through OrderService, and the posting
	 * fields are null.
	 */
	SALES_ORDER("Sales order", "sales_orders", "sales_order_lines", "order_id",
			"order_number", "customer_id", "order_date", "expected_ship_date",
			"unit_price", "revenue_account_id", null,
			"customers", null, null, null, null, null, false, null),

	PURCHASE_ORDER("Purchase order", "purchase_orders", "purchase_order_lines", "order_id",
			"order_number", "supplier_id", "order_date", "expected_receipt_date",
			"unit_cost", "expense_account_id", null,
			"suppliers", null, null, null, null, null, false, null);

	/** The side a document's revenue, expense, and tax lines post to; the control line takes the other. */
	enum Side {
		DEBIT, CREDIT
	}

	final String label;
	final String table;
	final String linesTable;
	/** The lines' column naming their document. */
	final String lineDocument;
	final String number;
	final String party;
	final String date;
	/** The optional second date: due_date, an order's expected date, or null for credit notes. */
	final String dueDate;
	/** The lines' unit price or unit cost column. */
	final String price;
	/** The lines' revenue or expense account column. */
	final String account;
	/** The product column a line without an account falls back to (spec/domain.md §3). */
	final String productAccount;
	final String partyTable;
	/** The party's A/R or A/P control account column. */
	final String control;
	final Side detailSide;
	/** The view giving each document's applied amount, balance, and settlement status. */
	final String balances;
	final String balancesId;
	final String settlement;
	/** Whether lines can come from an order, which freezes the document's content (spec/domain.md §6.4). */
	final boolean orderLines;
	/** Rows for each application to or of the document (:id), which block unposting. */
	final String applications;

	DocumentKind(String label, String table, String linesTable, String lineDocument, String number, String party,
			String date, String dueDate, String price, String account, String productAccount, String partyTable,
			String control, Side detailSide, String balances, String balancesId, String settlement, boolean orderLines,
			String applications) {
		this.label = label;
		this.table = table;
		this.linesTable = linesTable;
		this.lineDocument = lineDocument;
		this.number = number;
		this.party = party;
		this.date = date;
		this.dueDate = dueDate;
		this.price = price;
		this.account = account;
		this.productAccount = productAccount;
		this.partyTable = partyTable;
		this.control = control;
		this.detailSide = detailSide;
		this.balances = balances;
		this.balancesId = balancesId;
		this.settlement = settlement;
		this.orderLines = orderLines;
		this.applications = applications;
	}
}
