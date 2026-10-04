package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales and purchase orders (spec/api.md §5.10, spec/domain.md §6), which are
 * drafted like invoices (DocumentService) and never post. This service reads
 * them, moves them through their lifecycle, and fulfils them into draft
 * invoices, bills, and stock movements.
 *
 * <p>Fulfilment quantities are derived by the schema's fulfilment views
 * (domain §6.2), counting draft invoices and unposted movements, so deleting
 * a fulfilment document returns its quantity to the order. Requested
 * quantities are capped at what remains; an empty request takes the whole
 * remainder of every eligible line.
 */
@Service
public class OrderService {

	/** The two order kinds, by their schema names. */
	public enum Kind {
		SALES(DocumentKind.SALES_ORDER, DocumentKind.SALES_INVOICE, "sales_order_fulfilment",
				"sales_order_line_fulfilment", "invoiced_status", "shipped_status", "qty_invoiced", "qty_shipped",
				"qty_to_invoice", "qty_to_ship", "issue", -1, "sales_order_line"),
		PURCHASE(DocumentKind.PURCHASE_ORDER, DocumentKind.PURCHASE_BILL, "purchase_order_fulfilment",
				"purchase_order_line_fulfilment", "billed_status", "received_status", "qty_billed", "qty_received",
				"qty_to_bill", "qty_to_receive", "receipt", 1, "purchase_order_line");

		final DocumentKind order;
		/** The document an order is invoiced or billed into. */
		final DocumentKind document;
		final String headerView;
		final String lineView;
		final String documentStatus;
		final String stockStatus;
		final String documented;
		final String stocked;
		final String toDocument;
		final String toStock;
		final String movementType;
		/** The sign of a fulfilment movement's quantity: shipping issues, receiving receives. */
		final int movementSign;
		final String sourceType;

		Kind(DocumentKind order, DocumentKind document, String headerView, String lineView, String documentStatus,
				String stockStatus, String documented, String stocked, String toDocument, String toStock,
				String movementType, int movementSign, String sourceType) {
			this.order = order;
			this.document = document;
			this.headerView = headerView;
			this.lineView = lineView;
			this.documentStatus = documentStatus;
			this.stockStatus = stockStatus;
			this.documented = documented;
			this.stocked = stocked;
			this.toDocument = toDocument;
			this.toStock = toStock;
			this.movementType = movementType;
			this.movementSign = movementSign;
			this.sourceType = sourceType;
		}
	}

	private final JdbcClient jdbc;
	private final JsonMapper json;

	public OrderService(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	// ---- read shapes ----

	public record SalesOrder(int id, String orderNumber, int customerId, String orderDate, String expectedShipDate,
			String currencyCode, String status, String total, String invoicedStatus, String shippedStatus,
			String reference, String memo) {
	}

	public record PurchaseOrder(int id, String orderNumber, int supplierId, String orderDate, String expectedReceiptDate,
			String currencyCode, String status, String total, String billedStatus, String receivedStatus,
			String reference, String memo) {
	}

	/** An invoice line's fields, with order_line_id the line's own id, and the fulfilment quantities. */
	public record SalesOrderLine(int lineNo, Integer productId, String description, String quantity, String unitPrice,
			String taxCode, String taxRate, String lineSubtotal, String taxAmount, String lineTotal,
			Integer revenueAccountId, int orderLineId, String qtyInvoiced, String qtyShipped, String qtyToInvoice,
			String qtyToShip) {
	}

	public record PurchaseOrderLine(int lineNo, Integer productId, String description, String quantity, String unitCost,
			String taxCode, String taxRate, String lineSubtotal, String taxAmount, String lineTotal,
			Integer expenseAccountId, int orderLineId, String qtyBilled, String qtyReceived, String qtyToBill,
			String qtyToReceive) {
	}

	private static String select(Kind k) {
		DocumentKind o = k.order;
		return "SELECT o.id, o.order_number, o." + o.party + ", o.order_date::text AS order_date, o." + o.dueDate + "::text AS "
				+ o.dueDate + ", o.currency_code, o.status, o.total::text AS total, f." + k.documentStatus + ", f."
				+ k.stockStatus + ", o.reference, o.memo FROM " + o.table + " o JOIN " + k.headerView
				+ " f ON f.order_id = o.id";
	}

	/** Newest first, then by id descending. */
	public <T> List<T> list(Kind k, Class<T> shape) {
		return jdbc.sql(select(k) + " ORDER BY o.order_date DESC, o.id DESC").query(shape).list();
	}

	public <T> T get(Kind k, int id, Class<T> shape) {
		return jdbc.sql(select(k) + " WHERE o.id = ?").param(id).query(shape).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public <T> List<T> lines(Kind k, int id, Class<T> shape) {
		status(k, id, false);
		DocumentKind o = k.order;
		return jdbc.sql("""
				SELECT l.line_no, l.product_id, l.description, l.quantity::text AS quantity, l.%1$s::text AS %1$s,
				    l.tax_code, l.tax_rate::text AS tax_rate, l.line_subtotal::text AS line_subtotal,
				    l.tax_amount::text AS tax_amount, l.line_total::text AS line_total, l.%2$s, l.id AS order_line_id,
				    f.%4$s::text AS %4$s, f.%5$s::text AS %5$s, f.%6$s::text AS %6$s, f.%7$s::text AS %7$s
				FROM %3$s l JOIN %8$s f ON f.order_line_id = l.id
				WHERE l.order_id = ? ORDER BY l.line_no""".formatted(o.price, o.account, o.linesTable, k.documented,
				k.stocked, k.toDocument, k.toStock, k.lineView))
				.param(id).query(shape).list();
	}

	// ---- lifecycle (domain §6.1) ----

	/** Draft to open; an order needs at least one line (422). */
	@Transactional
	public void confirm(Kind k, int id) {
		requireStatus(k, id, "draft", "confirmed");
		if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + k.order.linesTable + " WHERE order_id = ?)").param(id)
				.query(Boolean.class).single()) {
			throw ServiceException.unprocessable(k.order.label + " " + id + " has no lines");
		}
		setStatus(k, id, "open");
	}

	/** Open to closed, by hand: fully fulfilled orders are not closed automatically. */
	@Transactional
	public void close(Kind k, int id) {
		requireStatus(k, id, "open", "closed");
		setStatus(k, id, "closed");
	}

	/** A draft always cancels; an open order only while nothing has been fulfilled against it. */
	@Transactional
	public void cancel(Kind k, int id) {
		String status = status(k, id, true);
		if (!status.equals("draft") && !status.equals("open")) {
			throw ServiceException.conflict(k.order.label + " " + id + " is " + status + " and cannot be cancelled");
		}
		if (status.equals("open") && jdbc.sql("SELECT COALESCE(sum(" + k.documented + " + " + k.stocked + "), 0) > 0 FROM "
				+ k.lineView + " WHERE order_id = ?").param(id).query(Boolean.class).single()) {
			throw ServiceException.conflict(k.order.label + " " + id + " has been fulfilled in part and cannot be cancelled");
		}
		setStatus(k, id, "cancelled");
	}

	// ---- fulfilment (domain §6.3) ----

	/** A requested order line and quantity. */
	public record LineQuantity(Integer orderLineId, String quantity) {
	}

	public record DocumentFromOrder(String number, String date, String dueDate, List<LineQuantity> lines) {
		public DocumentFromOrder {
			number = trim(number);
			date = trim(date);
			dueDate = dueDate == null || dueDate.isBlank() ? null : dueDate.strip();
			lines = lines == null ? List.of() : lines;
		}
	}

	public record StockFromOrder(Integer warehouseId, String movementDate, String reference, List<LineQuantity> lines) {
		public StockFromOrder {
			movementDate = movementDate == null || movementDate.isBlank() ? null : movementDate.strip();
			lines = lines == null ? List.of() : lines;
		}
	}

	/**
	 * Invoices a sales order or bills a purchase order: a draft document for
	 * the order's party and currency, referencing the order number, with one
	 * line per order line with something left, copying its product,
	 * description, price, account, and tax, numbered in order-line order.
	 * 422 if nothing is left.
	 */
	@Transactional
	public int document(Kind k, int id, DocumentFromOrder in) {
		DocumentKind d = k.document;
		require(in.number(), d.number);
		require(in.date(), d.date);
		requireStatus(k, id, "open", "fulfilled");
		int document = jdbc.sql("INSERT INTO " + d.table + " (" + d.number + ", " + d.party + ", " + d.date
				+ ", due_date, currency_code, reference) SELECT :number, " + d.party
				+ ", CAST(:date AS date), CAST(:dueDate AS date), currency_code, order_number FROM " + k.order.table
				+ " WHERE id = :order RETURNING id").paramSource(Params.of(in, "order", id)).query(Integer.class).single();
		int lines = jdbc.sql("""
				WITH picked AS (%1$s)
				INSERT INTO %2$s (%3$s, line_no, product_id, description, quantity, %4$s, %5$s, tax_code, tax_rate, order_line_id)
				SELECT :document, row_number() OVER (ORDER BY l.id), l.product_id, l.description, p.qty, l.%4$s, l.%5$s,
				    l.tax_code, l.tax_rate, l.id
				FROM picked p JOIN %6$s l ON l.id = p.order_line_id
				WHERE p.qty > 0""".formatted(picked(k, k.toDocument), d.linesTable, d.lineDocument, d.price, d.account,
				k.order.linesTable))
				.param("order", id).param("requested", requested(in.lines())).param("document", document).update();
		if (lines == 0) {
			throw ServiceException.unprocessable("nothing is left to " + (k == Kind.SALES ? "invoice" : "bill")
					+ " on " + k.order.label.toLowerCase() + " " + id);
		}
		return document;
	}

	/**
	 * Ships a sales order or receives a purchase order: one draft movement
	 * per line with a tracked, active product and something left to ship or
	 * receive, from the given warehouse, dated movement_date (default today).
	 * A shipment is costed at the warehouse's moving-average cost for the
	 * product; a receipt at the order line's cost converted to base at the
	 * order currency's rate on the movement date (422 if there is none).
	 * Returns the movements' ids; 422 if nothing is left.
	 */
	@Transactional
	public List<Integer> stock(Kind k, int id, StockFromOrder in) {
		if (in.warehouseId() == null || in.warehouseId() <= 0) {
			throw ServiceException.badRequest("warehouse_id is required");
		}
		requireStatus(k, id, "open", "fulfilled");
		String cost;
		if (k == Kind.SALES) {
			cost = "COALESCE((SELECT avg_unit_cost FROM stock_on_hand WHERE product_id = l.product_id AND warehouse_id = :warehouse), 0)";
		} else {
			boolean rated = jdbc.sql("""
					SELECT o.currency_code = (SELECT base_currency FROM gl_settings) OR EXISTS (
					    SELECT 1 FROM exchange_rates r WHERE r.currency_code = o.currency_code
					    AND r.rate_date <= COALESCE(CAST(:date AS date), current_date))
					FROM purchase_orders o WHERE o.id = :order""")
					.param("date", in.movementDate()).param("order", id).query(Boolean.class).single();
			if (!rated) {
				throw ServiceException.unprocessable("no exchange rate for the order's currency on or before the movement date");
			}
			cost = """
					round(l.unit_cost * (SELECT CASE WHEN o.currency_code = (SELECT base_currency FROM gl_settings) THEN 1
					    ELSE (SELECT rate FROM exchange_rates r WHERE r.currency_code = o.currency_code
					          AND r.rate_date <= COALESCE(CAST(:date AS date), current_date) ORDER BY r.rate_date DESC LIMIT 1)
					    END FROM purchase_orders o WHERE o.id = :order), 4)""";
		}
		List<Integer> ids = jdbc.sql("""
				WITH picked AS (%1$s)
				INSERT INTO stock_movements (product_id, warehouse_id, movement_type, movement_date, quantity, unit_cost,
				    source_type, source_id, reference)
				SELECT l.product_id, :warehouse, '%2$s', COALESCE(CAST(:date AS date), current_date), %3$d * p.qty, %4$s,
				    '%5$s', l.id, :reference
				FROM picked p JOIN %6$s l ON l.id = p.order_line_id
				JOIN products pr ON pr.id = l.product_id AND pr.track_inventory AND pr.is_active
				WHERE p.qty > 0
				ORDER BY l.id
				RETURNING id""".formatted(picked(k, k.toStock), k.movementType, k.movementSign, cost, k.sourceType,
				k.order.linesTable))
				.param("order", id).param("requested", requested(in.lines())).param("warehouse", in.warehouseId())
				.param("date", in.movementDate()).param("reference", in.reference())
				.query(Integer.class).list();
		if (ids.isEmpty()) {
			throw ServiceException.unprocessable("nothing is left to " + (k == Kind.SALES ? "ship" : "receive")
					+ " on " + k.order.label.toLowerCase() + " " + id);
		}
		return ids.stream().sorted().toList();
	}

	/**
	 * The quantity to take from each of the order's lines: min(what remains,
	 * what was requested), or what remains when nothing was requested. The
	 * request arrives as JSON (:requested), since Spring would expand an
	 * array parameter into a list.
	 */
	private static String picked(Kind k, String remaining) {
		return """
				SELECT f.order_line_id,
				    CASE WHEN jsonb_array_length(CAST(:requested AS jsonb)) = 0 THEN f.%1$s
				         ELSE LEAST(f.%1$s, COALESCE(r.quantity, 0)) END AS qty
				FROM %2$s f
				LEFT JOIN (SELECT order_line_id, sum(round(quantity, 4)) AS quantity
				           FROM jsonb_to_recordset(CAST(:requested AS jsonb)) AS x(order_line_id int, quantity numeric)
				           GROUP BY order_line_id) r ON r.order_line_id = f.order_line_id
				WHERE f.order_id = :order""".formatted(remaining, k.lineView);
	}

	private String requested(List<LineQuantity> lines) {
		return json.writeValueAsString(lines.stream().filter(l -> l != null && l.orderLineId() != null).toList());
	}

	// ---- status ----

	/** The order's status, locked if asked; 404 if it does not exist. */
	private String status(Kind k, int id, boolean lock) {
		return jdbc.sql("SELECT status FROM " + k.order.table + " WHERE id = ?" + (lock ? " FOR UPDATE" : "")).param(id)
				.query(String.class).optional().orElseThrow(ServiceException::notFound);
	}

	private void requireStatus(Kind k, int id, String required, String action) {
		String status = status(k, id, true);
		if (!status.equals(required)) {
			throw ServiceException.conflict(k.order.label + " " + id + " is " + status + "; only a" + (required.equals("open")
					? "n " : " ") + required + " order can be " + action);
		}
	}

	private void setStatus(Kind k, int id, String status) {
		jdbc.sql("UPDATE " + k.order.table + " SET status = ? WHERE id = ?").params(status, id).update();
	}
}
