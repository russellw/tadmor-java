package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.decimal;
import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drafting invoices, bills, and credit notes, and reading them (spec/api.md
 * §5.9). A
 * document is created as a draft and may be replaced, header and lines
 * together, or deleted, only while it is one (spec/domain.md §1).
 *
 * <p>The schema computes the money: each line's subtotal, tax, and total
 * are generated columns (domain §2), and a trigger keeps a draft header's
 * totals summed from its lines. It also refuses a zero quantity, a due date
 * before the document date, unknown references, and duplicate numbers.
 */
@Service
public class DocumentService {

	private final JdbcClient jdbc;

	public DocumentService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- request bodies, one per kind, reduced to one internal form ----

	/** A document as every kind writes it, with the kind's field names behind it. */
	public record DocumentInput(String number, Integer partyId, String date, String dueDate, String currencyCode,
			String reference, String memo, List<LineInput> lines) {
		public DocumentInput {
			number = trim(number);
			date = trim(date);
			dueDate = dueDate == null || dueDate.isBlank() ? null : dueDate.strip();
			currencyCode = trim(currencyCode);
			lines = lines == null ? List.of() : lines;
		}
	}

	/** A line: the tax rate is a snapshot sent with it, never looked up from the tax code. */
	public record LineInput(Integer productId, String description, String quantity, String price, Integer accountId,
			String taxCode, String taxRate) {
		public LineInput {
			description = trim(description);
			quantity = decimal(quantity, "1");
			price = decimal(price, "0");
			taxRate = decimal(taxRate, "0");
		}
	}

	/** A request body that names its fields the way one document kind does. */
	public interface DocumentRequest {
		DocumentInput document();
	}

	public record SalesInvoiceInput(String invoiceNumber, Integer customerId, String invoiceDate, String dueDate,
			String currencyCode, String reference, String memo, List<SalesLineInput> lines) implements DocumentRequest {
		@Override
		public DocumentInput document() {
			return new DocumentInput(invoiceNumber, customerId, invoiceDate, dueDate, currencyCode, reference, memo,
					lines == null ? null : lines.stream().map(SalesLineInput::line).toList());
		}
	}

	public record SalesLineInput(Integer productId, String description, String quantity, String unitPrice,
			Integer revenueAccountId, String taxCode, String taxRate) {
		LineInput line() {
			return new LineInput(productId, description, quantity, unitPrice, revenueAccountId, taxCode, taxRate);
		}
	}

	public record PurchaseBillInput(String billNumber, Integer supplierId, String billDate, String dueDate,
			String currencyCode, String reference, String memo, List<PurchaseLineInput> lines) implements DocumentRequest {
		@Override
		public DocumentInput document() {
			return new DocumentInput(billNumber, supplierId, billDate, dueDate, currencyCode, reference, memo,
					lines == null ? null : lines.stream().map(PurchaseLineInput::line).toList());
		}
	}

	public record PurchaseLineInput(Integer productId, String description, String quantity, String unitCost,
			Integer expenseAccountId, String taxCode, String taxRate) {
		LineInput line() {
			return new LineInput(productId, description, quantity, unitCost, expenseAccountId, taxCode, taxRate);
		}
	}

	public record SalesCreditNoteInput(String creditNoteNumber, Integer customerId, String creditNoteDate,
			String currencyCode, String reference, String memo, List<SalesLineInput> lines) implements DocumentRequest {
		@Override
		public DocumentInput document() {
			return new DocumentInput(creditNoteNumber, customerId, creditNoteDate, null, currencyCode, reference, memo,
					lines == null ? null : lines.stream().map(SalesLineInput::line).toList());
		}
	}

	public record PurchaseCreditNoteInput(String creditNoteNumber, Integer supplierId, String creditNoteDate,
			String currencyCode, String reference, String memo, List<PurchaseLineInput> lines) implements DocumentRequest {
		@Override
		public DocumentInput document() {
			return new DocumentInput(creditNoteNumber, supplierId, creditNoteDate, null, currencyCode, reference, memo,
					lines == null ? null : lines.stream().map(PurchaseLineInput::line).toList());
		}
	}

	// ---- read shapes: the kind's key fields, then the fields all share ----

	public record SalesInvoice(int id, String invoiceNumber, int customerId, String invoiceDate, String dueDate,
			String paymentStatus, String currencyCode, String status, String total, String amountApplied, String balance,
			Integer journalEntryId, String reference, String memo) {
	}

	public record PurchaseBill(int id, String billNumber, int supplierId, String billDate, String dueDate,
			String paymentStatus, String currencyCode, String status, String total, String amountApplied, String balance,
			Integer journalEntryId, String reference, String memo) {
	}

	public record SalesCreditNote(int id, String creditNoteNumber, int customerId, String creditNoteDate,
			String applicationStatus, String currencyCode, String status, String total, String amountApplied,
			String balance, Integer journalEntryId, String reference, String memo) {
	}

	public record PurchaseCreditNote(int id, String creditNoteNumber, int supplierId, String creditNoteDate,
			String applicationStatus, String currencyCode, String status, String total, String amountApplied,
			String balance, Integer journalEntryId, String reference, String memo) {
	}

	/** Also the shape of sales credit-note lines, whose order_line_id is always null. */
	public record InvoiceLine(int lineNo, Integer productId, String description, String quantity, String unitPrice,
			String taxCode, String taxRate, String lineSubtotal, String taxAmount, String lineTotal,
			Integer revenueAccountId, Integer orderLineId) {
	}

	/** Also the shape of purchase credit-note lines. */
	public record BillLine(int lineNo, Integer productId, String description, String quantity, String unitCost,
			String taxCode, String taxRate, String lineSubtotal, String taxAmount, String lineTotal,
			Integer expenseAccountId, Integer orderLineId) {
	}

	// ---- reads ----

	private static String select(DocumentKind k) {
		return "SELECT b." + k.balancesId + " AS id, b." + k.number + ", b." + k.party + ", b." + k.date + "::text AS "
				+ k.date + (k.hasDueDate ? ", b.due_date::text AS due_date" : "") + ", b." + k.settlement + """
				, b.currency_code, b.status, b.total::numeric(19,4)::text AS total,
				    b.amount_applied::numeric(19,4)::text AS amount_applied, b.balance::numeric(19,4)::text AS balance,
				    d.journal_entry_id, d.reference, d.memo
				FROM """ + " " + k.balances + " b JOIN " + k.table + " d ON d.id = b." + k.balancesId;
	}

	/** Newest first, then by id descending. */
	public <T> List<T> list(DocumentKind k, Class<T> shape) {
		return jdbc.sql(select(k) + " ORDER BY b." + k.date + " DESC, b." + k.balancesId + " DESC").query(shape).list();
	}

	public <T> T get(DocumentKind k, int id, Class<T> shape) {
		return jdbc.sql(select(k) + " WHERE d.id = ?").param(id).query(shape).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public <T> List<T> lines(DocumentKind k, int id, Class<T> shape) {
		requireExists(k, id);
		return jdbc.sql("SELECT line_no, product_id, description, quantity::text AS quantity, " + k.price
				+ "::text AS " + k.price + """
				, tax_code, tax_rate::text AS tax_rate, line_subtotal::text AS line_subtotal,
				    tax_amount::text AS tax_amount, line_total::text AS line_total, """ + " " + k.account + ", "
				+ (k.orderLines ? "order_line_id" : "NULL::int AS order_line_id") + " FROM " + k.linesTable + " WHERE "
				+ k.lineDocument + " = ? ORDER BY line_no").param(id).query(shape).list();
	}

	// ---- drafts ----

	@Transactional
	public int create(DocumentKind k, DocumentInput in) {
		validate(k, in);
		int id = jdbc.sql("INSERT INTO " + k.table + " (" + k.number + ", " + k.party + ", " + k.date
				+ (k.hasDueDate ? ", due_date" : "") + ", currency_code, reference, memo) VALUES (:number, :partyId, "
				+ "CAST(:date AS date)" + (k.hasDueDate ? ", CAST(:dueDate AS date)" : "")
				+ ", :currencyCode, :reference, :memo) RETURNING id").paramSource(in).query(Integer.class).single();
		insertLines(k, id, in.lines());
		return id;
	}

	/**
	 * Replaces the draft's header and its whole line set. A document produced
	 * from an order cannot be edited (409), though it may be deleted.
	 */
	@Transactional
	public void update(DocumentKind k, int id, DocumentInput in) {
		validate(k, in);
		requireDraft(k, id);
		if (k.orderLines && jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + k.linesTable + " WHERE " + k.lineDocument
				+ " = ? AND order_line_id IS NOT NULL)").param(id).query(Boolean.class).single()) {
			throw ServiceException.conflict(k.label + " " + id + " was produced from an order and cannot be edited");
		}
		jdbc.sql("UPDATE " + k.table + " SET " + k.number + " = :number, " + k.party + " = :partyId, " + k.date
				+ " = CAST(:date AS date)" + (k.hasDueDate ? ", due_date = CAST(:dueDate AS date)" : "")
				+ ", currency_code = :currencyCode, reference = :reference, memo = :memo WHERE id = :id")
				.paramSource(Params.of(in, "id", id)).update();
		jdbc.sql("DELETE FROM " + k.linesTable + " WHERE " + k.lineDocument + " = ?").param(id).update();
		insertLines(k, id, in.lines());
	}

	@Transactional
	public void delete(DocumentKind k, int id) {
		requireDraft(k, id);
		jdbc.sql("DELETE FROM " + k.table + " WHERE id = ?").param(id).update();
	}

	private void insertLines(DocumentKind k, int id, List<LineInput> lines) {
		String insert = "INSERT INTO " + k.linesTable + " (" + k.lineDocument + ", line_no, product_id, description, "
				+ "quantity, " + k.price + ", " + k.account + ", tax_code, tax_rate) VALUES (:document, :lineNo, "
				+ ":productId, :description, CAST(:quantity AS numeric), CAST(:price AS numeric), :accountId, :taxCode, "
				+ "CAST(:taxRate AS numeric))";
		for (int i = 0; i < lines.size(); i++) {
			jdbc.sql(insert).paramSource(Params.of(lines.get(i), "document", id, "lineNo", i + 1)).update();
		}
	}

	/** 400 for a missing header field or line description (spec/api.md §5.9). */
	private static void validate(DocumentKind k, DocumentInput in) {
		require(in.number(), k.number);
		if (in.partyId() == null || in.partyId() <= 0) {
			throw ServiceException.badRequest(k.party + " is required");
		}
		require(in.date(), k.date);
		require(in.currencyCode(), "currency_code");
		for (int i = 0; i < in.lines().size(); i++) {
			if (in.lines().get(i) == null || in.lines().get(i).description().isEmpty()) {
				throw ServiceException.badRequest("line " + (i + 1) + ": description is required");
			}
		}
	}

	private void requireExists(DocumentKind k, int id) {
		if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + k.table + " WHERE id = ?)").param(id).query(Boolean.class)
				.single()) {
			throw ServiceException.notFound();
		}
	}

	/** Locks the document; 404 if it does not exist, 409 unless it is a draft. */
	private void requireDraft(DocumentKind k, int id) {
		String status = jdbc.sql("SELECT status FROM " + k.table + " WHERE id = ? FOR UPDATE").param(id)
				.query(String.class).optional().orElseThrow(ServiceException::notFound);
		if (!status.equals("draft")) {
			throw ServiceException.conflict(k.label + " " + id + " is " + status + ", not a draft");
		}
	}
}
