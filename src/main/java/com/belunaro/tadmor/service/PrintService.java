package com.belunaro.tadmor.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.belunaro.tadmor.printing.Layout;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Printable documents (spec/api.md §5.11, spec/domain.md §11): gathers a
 * document's header, counterparty, lines, and our own organization (the one
 * flagged is_self, if any) and renders them through the shared Layout.
 */
@Service
public class PrintService {

	/** The printable collections, with their labels and filename prefixes. */
	public enum Printable {
		SALES_INVOICE(DocumentKind.SALES_INVOICE, "sales-invoices", "invoice", "Invoice", "Invoice", "Invoice no.",
				"Invoice date", "Due date", "UNIT PRICE", "BILL TO", "Amount paid", "Balance due"),
		PURCHASE_BILL(DocumentKind.PURCHASE_BILL, "purchase-bills", "bill", "Bill", "Bill", "Bill no.", "Bill date",
				"Due date", "UNIT COST", "SUPPLIER", "Amount paid", "Balance due"),
		SALES_CREDIT_NOTE(DocumentKind.SALES_CREDIT_NOTE, "sales-credit-notes", "credit-note", "Credit Note",
				"Credit Note", "Credit note no.", "Credit note date", null, "UNIT PRICE", "CREDIT TO", "Amount applied",
				"Unapplied"),
		PURCHASE_CREDIT_NOTE(DocumentKind.PURCHASE_CREDIT_NOTE, "purchase-credit-notes", "supplier-credit",
				"Supplier Credit", "Credit Note", "Credit note no.", "Credit note date", null, "UNIT COST", "SUPPLIER",
				"Amount applied", "Unapplied"),
		SALES_ORDER(DocumentKind.SALES_ORDER, "sales-orders", "sales-order", "Sales Order", "Sales Order", "Order no.",
				"Order date", "Expected ship", "UNIT PRICE", "CUSTOMER", null, null),
		PURCHASE_ORDER(DocumentKind.PURCHASE_ORDER, "purchase-orders", "purchase-order", "Purchase Order",
				"Purchase Order", "Order no.", "Order date", "Expected receipt", "UNIT COST", "SUPPLIER", null, null);

		final DocumentKind document;
		final String collection;
		final String filePrefix;
		/** The title and footer word. */
		final String kind;
		/** The email subject's word. */
		final String mailLabel;
		final String numberLabel;
		final String dateLabel;
		final String dueLabel;
		final String unitLabel;
		final String partyLabel;
		final String appliedLabel;
		final String balanceLabel;

		Printable(DocumentKind document, String collection, String filePrefix, String kind, String mailLabel,
				String numberLabel, String dateLabel, String dueLabel, String unitLabel, String partyLabel,
				String appliedLabel, String balanceLabel) {
			this.document = document;
			this.collection = collection;
			this.filePrefix = filePrefix;
			this.kind = kind;
			this.mailLabel = mailLabel;
			this.numberLabel = numberLabel;
			this.dateLabel = dateLabel;
			this.dueLabel = dueLabel;
			this.unitLabel = unitLabel;
			this.partyLabel = partyLabel;
			this.appliedLabel = appliedLabel;
			this.balanceLabel = balanceLabel;
		}

		public static Optional<Printable> ofCollection(String collection) {
			return Arrays.stream(values()).filter(p -> p.collection.equals(collection)).findFirst();
		}
	}

	/** A rendered document: the PDF, its download filename, and what to call it in an email. */
	public record Printed(byte[] pdf, String filename, String subject, String number) {
	}

	private final JdbcClient jdbc;

	public PrintService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record Header(String number, String date, String dueDate, String currencyCode, String status,
			String subtotal, String taxTotal, String total, String applied, String balance, String reference, String memo,
			String partyName, String legalName, String taxId, String email, int organizationId, Integer billingAddressId) {
	}

	private record Address(String line1, String line2, String city, String region, String postalCode, String country) {
		List<String> lines() {
			return Layout.address(line1, line2, city, region, postalCode, country);
		}
	}

	private Header header(Printable p, int id) {
		DocumentKind k = p.document;
		boolean balances = k.balances != null;
		return jdbc.sql("""
				SELECT d.%1$s AS number, d.%2$s::text AS date, %3$s AS due_date, d.currency_code, d.status,
				    d.subtotal::text AS subtotal, d.tax_total::text AS tax_total, d.total::text AS total,
				    %4$s AS applied, %5$s AS balance, d.reference, d.memo,
				    o.name AS party_name, o.legal_name, o.tax_id, o.email, o.id AS organization_id,
				    %6$s AS billing_address_id
				FROM %7$s d
				JOIN %8$s p ON p.id = d.%9$s
				JOIN organizations o ON o.id = p.organization_id
				%10$s
				WHERE d.id = ?""".formatted(k.number, k.date, k.dueDate != null ? "d." + k.dueDate + "::text" : "NULL::text",
				balances ? "b.amount_applied::numeric(19,4)::text" : "NULL::text",
				balances ? "b.balance::numeric(19,4)::text" : "NULL::text",
				k == DocumentKind.SALES_INVOICE ? "d.billing_address_id" : "NULL::int", k.table, k.partyTable, k.party,
				balances ? "JOIN " + k.balances + " b ON b." + k.balancesId + " = d.id" : ""))
				.param(id).query(Header.class).optional().orElseThrow(ServiceException::notFound);
	}

	/** The billing address if the document names one, else the organization's first address. */
	private List<String> address(int organizationId, Integer preferred) {
		return jdbc.sql("""
				SELECT a.line1, a.line2, a.city, a.region, a.postal_code, co.name AS country
				FROM addresses a LEFT JOIN countries co ON co.code = a.country_code
				WHERE a.id = COALESCE(CAST(:preferred AS int),
				    (SELECT id FROM addresses WHERE organization_id = :organization ORDER BY id LIMIT 1))""")
				.param("preferred", preferred).param("organization", organizationId)
				.query(Address.class).optional().map(Address::lines).orElse(List.of());
	}

	/** Our own organization, the one flagged is_self, or null. */
	private Layout.Party seller() {
		record Self(int id, String name, String legalName, String taxId) {
		}
		return jdbc.sql("SELECT id, name, legal_name, tax_id FROM organizations WHERE is_self").query(Self.class).optional()
				.map(s -> new Layout.Party(s.name(), s.legalName(), s.taxId(), address(s.id(), null))).orElse(null);
	}

	private List<Layout.Line> lines(Printable p, int id) {
		DocumentKind k = p.document;
		return jdbc.sql("SELECT line_no, description, quantity::text AS quantity, " + k.price + "::text AS unit, "
				+ "tax_rate::text AS tax_rate, line_subtotal::text AS subtotal FROM " + k.linesTable + " WHERE "
				+ k.lineDocument + " = ? ORDER BY line_no").param(id).query(Layout.Line.class).list();
	}

	public Printed print(Printable p, int id) {
		Header h = header(p, id);
		List<Layout.Meta> meta = new ArrayList<>();
		meta.add(new Layout.Meta(p.numberLabel, h.number()));
		meta.add(new Layout.Meta(p.dateLabel, h.date()));
		if (h.dueDate() != null) {
			meta.add(new Layout.Meta(p.dueLabel, h.dueDate()));
		}
		meta.add(new Layout.Meta("Currency", h.currencyCode()));
		Layout.Party party = new Layout.Party(h.partyName(), h.legalName(), h.taxId(),
				address(h.organizationId(), h.billingAddressId()));
		byte[] pdf = Layout.render(new Layout.Document(p.kind, h.number(), h.status(), h.currencyCode(), meta,
				p.partyLabel, party, seller(), p.unitLabel, h.subtotal(), h.taxTotal(), h.total(), h.applied(), h.balance(),
				p.appliedLabel, p.balanceLabel, h.reference(), h.memo(), lines(p, id)));
		return new Printed(pdf, filename(p, h.number()), p.mailLabel + " " + h.number(), h.number());
	}

	/** The counterparty organization's email, empty if it has none; 404 for an unknown document. */
	public Optional<String> recipient(Printable p, int id) {
		return Optional.ofNullable(header(p, id).email()).filter(e -> !e.isBlank());
	}

	/** "<prefix>-<number>.pdf", every character of the number outside [A-Za-z0-9._-] replaced by '-'. */
	static String filename(Printable p, String number) {
		return p.filePrefix + "-" + number.replaceAll("[^A-Za-z0-9._-]", "-") + ".pdf";
	}
}
