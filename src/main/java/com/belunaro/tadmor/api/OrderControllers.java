package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.DocumentKind;
import com.belunaro.tadmor.service.DocumentService;
import com.belunaro.tadmor.service.DocumentService.DocumentRequest;
import com.belunaro.tadmor.service.DocumentService.PurchaseOrderInput;
import com.belunaro.tadmor.service.DocumentService.SalesOrderInput;
import com.belunaro.tadmor.service.OrderService;
import com.belunaro.tadmor.service.OrderService.DocumentFromOrder;
import com.belunaro.tadmor.service.OrderService.Kind;
import com.belunaro.tadmor.service.OrderService.LineQuantity;
import com.belunaro.tadmor.service.OrderService.PurchaseOrder;
import com.belunaro.tadmor.service.OrderService.PurchaseOrderLine;
import com.belunaro.tadmor.service.OrderService.SalesOrder;
import com.belunaro.tadmor.service.OrderService.SalesOrderLine;
import com.belunaro.tadmor.service.OrderService.StockFromOrder;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sales and purchase orders (spec/api.md §5.10). */
final class OrderControllers {

	private OrderControllers() {
	}

	/** The endpoints both kinds share; each binds its request body (I), read shape (D), and line shape (L). */
	abstract static class OrderController<I extends DocumentRequest, D, L> {

		final Kind kind;
		private final DocumentKind documentKind;
		private final Class<D> shape;
		private final Class<L> lineShape;
		final OrderService orders;
		private final DocumentService documents;

		OrderController(Kind kind, DocumentKind documentKind, Class<D> shape, Class<L> lineShape, OrderService orders,
				DocumentService documents) {
			this.kind = kind;
			this.documentKind = documentKind;
			this.shape = shape;
			this.lineShape = lineShape;
			this.orders = orders;
			this.documents = documents;
		}

		@GetMapping
		public List<D> list() {
			return orders.list(kind, shape);
		}

		@GetMapping("/{id}")
		public D get(@PathVariable Id id) {
			return orders.get(kind, id.value(), shape);
		}

		@GetMapping("/{id}/lines")
		public List<L> lines(@PathVariable Id id) {
			return orders.lines(kind, id.value(), lineShape);
		}

		@PostMapping
		public ResponseEntity<Map<String, Object>> create(@RequestBody I in) {
			return Responses.createdId(documents.create(documentKind, in.document()));
		}

		@PutMapping("/{id}")
		public ResponseEntity<Void> update(@PathVariable Id id, @RequestBody I in) {
			documents.update(documentKind, id.value(), in.document());
			return Responses.noContent();
		}

		@DeleteMapping("/{id}")
		public ResponseEntity<Void> delete(@PathVariable Id id) {
			documents.delete(documentKind, id.value());
			return Responses.noContent();
		}

		@PostMapping("/{id}/confirm")
		public ResponseEntity<Void> confirm(@PathVariable Id id) {
			orders.confirm(kind, id.value());
			return Responses.noContent();
		}

		@PostMapping("/{id}/close")
		public ResponseEntity<Void> close(@PathVariable Id id) {
			orders.close(kind, id.value());
			return Responses.noContent();
		}

		@PostMapping("/{id}/cancel")
		public ResponseEntity<Void> cancel(@PathVariable Id id) {
			orders.cancel(kind, id.value());
			return Responses.noContent();
		}

		ResponseEntity<Map<String, List<Integer>>> movements(int id, StockFromOrder in) {
			return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("movement_ids", orders.stock(kind, id, in)));
		}
	}

	public record InvoiceRequest(String invoiceNumber, String invoiceDate, String dueDate, List<LineQuantity> lines) {
	}

	public record BillRequest(String billNumber, String billDate, String dueDate, List<LineQuantity> lines) {
	}

	@RestController
	@RequestMapping("/api/sales-orders")
	static class SalesOrders extends OrderController<SalesOrderInput, SalesOrder, SalesOrderLine> {
		SalesOrders(OrderService orders, DocumentService documents) {
			super(Kind.SALES, DocumentKind.SALES_ORDER, SalesOrder.class, SalesOrderLine.class, orders, documents);
		}

		@PostMapping("/{id}/invoice")
		public ResponseEntity<Map<String, Integer>> invoice(@PathVariable Id id, @RequestBody InvoiceRequest in) {
			int invoice = orders.document(kind, id.value(),
					new DocumentFromOrder(in.invoiceNumber(), in.invoiceDate(), in.dueDate(), in.lines()));
			return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("invoice_id", invoice));
		}

		@PostMapping("/{id}/ship")
		public ResponseEntity<Map<String, List<Integer>>> ship(@PathVariable Id id, @RequestBody StockFromOrder in) {
			return movements(id.value(), in);
		}
	}

	@RestController
	@RequestMapping("/api/purchase-orders")
	static class PurchaseOrders extends OrderController<PurchaseOrderInput, PurchaseOrder, PurchaseOrderLine> {
		PurchaseOrders(OrderService orders, DocumentService documents) {
			super(Kind.PURCHASE, DocumentKind.PURCHASE_ORDER, PurchaseOrder.class, PurchaseOrderLine.class, orders,
					documents);
		}

		@PostMapping("/{id}/bill")
		public ResponseEntity<Map<String, Integer>> bill(@PathVariable Id id, @RequestBody BillRequest in) {
			int bill = orders.document(kind, id.value(),
					new DocumentFromOrder(in.billNumber(), in.billDate(), in.dueDate(), in.lines()));
			return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("bill_id", bill));
		}

		@PostMapping("/{id}/receive")
		public ResponseEntity<Map<String, List<Integer>>> receive(@PathVariable Id id, @RequestBody StockFromOrder in) {
			return movements(id.value(), in);
		}
	}
}
