package com.belunaro.tadmor.ui;

import java.util.Arrays;
import java.util.Optional;

import com.belunaro.tadmor.service.DocumentKind;
import com.belunaro.tadmor.service.DocumentService;
import com.belunaro.tadmor.service.OrderService;
import com.belunaro.tadmor.service.PrintService.Printable;
import com.belunaro.tadmor.service.SettlerKind;

/**
 * The screens of the six documents with lines (spec/domain.md §13.4, §13.6):
 * their labels, their field names in the API's vocabulary, and the records
 * that carry them. Invoices, bills, and credit notes post; orders fulfil.
 */
public enum DocScreen {

	SALES_INVOICE("sales-invoices", "Invoices", "invoice", DocumentKind.SALES_INVOICE, null, true, "invoice_number",
			"invoice_date", "due_date", "Due date", DocumentService.SalesInvoiceInput.class,
			DocumentService.SalesInvoice.class, DocumentService.InvoiceLine.class, null, Printable.SALES_INVOICE),
	PURCHASE_BILL("purchase-bills", "Bills", "bill", DocumentKind.PURCHASE_BILL, null, false, "bill_number", "bill_date",
			"due_date", "Due date", DocumentService.PurchaseBillInput.class, DocumentService.PurchaseBill.class,
			DocumentService.BillLine.class, null, Printable.PURCHASE_BILL),
	SALES_CREDIT_NOTE("sales-credit-notes", "Credit notes", "credit note", DocumentKind.SALES_CREDIT_NOTE, null, true,
			"credit_note_number", "credit_note_date", null, null, DocumentService.SalesCreditNoteInput.class,
			DocumentService.SalesCreditNote.class, DocumentService.InvoiceLine.class, SettlerKind.SALES_CREDIT_NOTE,
			Printable.SALES_CREDIT_NOTE),
	PURCHASE_CREDIT_NOTE("purchase-credit-notes", "Supplier credits", "supplier credit", DocumentKind.PURCHASE_CREDIT_NOTE,
			null, false, "credit_note_number", "credit_note_date", null, null, DocumentService.PurchaseCreditNoteInput.class,
			DocumentService.PurchaseCreditNote.class, DocumentService.BillLine.class, SettlerKind.PURCHASE_CREDIT_NOTE,
			Printable.PURCHASE_CREDIT_NOTE),
	SALES_ORDER("sales-orders", "Sales orders", "sales order", DocumentKind.SALES_ORDER, OrderService.Kind.SALES, true,
			"order_number", "order_date", "expected_ship_date", "Expected ship", DocumentService.SalesOrderInput.class,
			OrderService.SalesOrder.class, OrderService.SalesOrderLine.class, null, Printable.SALES_ORDER),
	PURCHASE_ORDER("purchase-orders", "Purchase orders", "purchase order", DocumentKind.PURCHASE_ORDER,
			OrderService.Kind.PURCHASE, false, "order_number", "order_date", "expected_receipt_date", "Expected receipt",
			DocumentService.PurchaseOrderInput.class, OrderService.PurchaseOrder.class, OrderService.PurchaseOrderLine.class,
			null, Printable.PURCHASE_ORDER);

	public final String path;
	public final String title;
	public final String singular;
	public final DocumentKind kind;
	/** Set for orders. */
	public final OrderService.Kind order;
	/** Sales side (customers, prices, revenue) or purchase side (suppliers, costs, expenses). */
	public final boolean sales;
	public final String numberField;
	public final String dateField;
	/** The optional second date's field and label, or null (credit notes). */
	public final String dueField;
	public final String dueLabel;
	public final Class<? extends DocumentService.DocumentRequest> request;
	public final Class<?> shape;
	public final Class<?> lineShape;
	/** Set for credit notes, which settle by application. */
	public final SettlerKind settler;
	public final Printable printable;

	DocScreen(String path, String title, String singular, DocumentKind kind, OrderService.Kind order, boolean sales,
			String numberField, String dateField, String dueField, String dueLabel,
			Class<? extends DocumentService.DocumentRequest> request, Class<?> shape, Class<?> lineShape,
			SettlerKind settler, Printable printable) {
		this.path = path;
		this.title = title;
		this.singular = singular;
		this.kind = kind;
		this.order = order;
		this.sales = sales;
		this.numberField = numberField;
		this.dateField = dateField;
		this.dueField = dueField;
		this.dueLabel = dueLabel;
		this.request = request;
		this.shape = shape;
		this.lineShape = lineShape;
		this.settler = settler;
		this.printable = printable;
	}

	public static Optional<DocScreen> of(String path) {
		return Arrays.stream(values()).filter(s -> s.path.equals(path)).findFirst();
	}

	// For templates.

	public String getPath() {
		return path;
	}

	public String getTitle() {
		return title;
	}

	public String getSingular() {
		return singular;
	}

	public boolean isOrder() {
		return order != null;
	}

	public boolean isSales() {
		return sales;
	}

	public boolean isCreditNote() {
		return settler != null;
	}

	public String getNumberField() {
		return numberField;
	}

	public String getDateField() {
		return dateField;
	}

	public String getDueField() {
		return dueField;
	}

	public String getDueLabel() {
		return dueLabel;
	}

	public String getPartyField() {
		return sales ? "customer_id" : "supplier_id";
	}

	public String getPartyChoices() {
		return sales ? "customers" : "suppliers";
	}

	public String getPartyLabel() {
		return sales ? "Customer" : "Supplier";
	}

	public String getPriceField() {
		return sales ? "unit_price" : "unit_cost";
	}

	public String getPriceLabel() {
		return sales ? "Unit price" : "Unit cost";
	}

	public String getAccountField() {
		return sales ? "revenue_account_id" : "expense_account_id";
	}

	public String getAccountLabel() {
		return sales ? "Revenue account" : "Expense account";
	}

	/** The read shape's settlement status field: payment status, application status, or none for orders. */
	public String getSettlementField() {
		return isOrder() ? null : isCreditNote() ? "application_status" : "payment_status";
	}

	/** The order's fulfilment verbs. */
	public String getDocumentVerb() {
		return sales ? "invoice" : "bill";
	}

	public String getStockVerb() {
		return sales ? "ship" : "receive";
	}

	/** The date a fulfilment step asks for: the new document's date, or the movements'. */
	public String dateFieldFor(String verb) {
		return switch (verb) {
		case "invoice" -> "invoice_date";
		case "bill" -> "bill_date";
		default -> "movement_date";
		};
	}
}
