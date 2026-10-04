package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.PaymentService;
import com.belunaro.tadmor.service.PaymentService.CustomerPayment;
import com.belunaro.tadmor.service.PaymentService.CustomerPaymentInput;
import com.belunaro.tadmor.service.PaymentService.Kind;
import com.belunaro.tadmor.service.PaymentService.PaymentRequest;
import com.belunaro.tadmor.service.PaymentService.SupplierPayment;
import com.belunaro.tadmor.service.PaymentService.SupplierPaymentInput;
import com.belunaro.tadmor.service.SettlementService;
import com.belunaro.tadmor.service.SettlementService.Applied;
import com.belunaro.tadmor.service.SettlementService.Application;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer and supplier payments (spec/api.md §5.9). Unposting is for administrators only (SecurityConfig). */
final class PaymentControllers {

	private PaymentControllers() {
	}

	/** The payment endpoints; each collection binds its request body (I) and read shape (D). */
	abstract static class PaymentController<I extends PaymentRequest, D> {

		private final Kind kind;
		private final Class<D> shape;
		private final PaymentService payments;
		private final SettlementService settlement;

		PaymentController(Kind kind, Class<D> shape, PaymentService payments, SettlementService settlement) {
			this.kind = kind;
			this.shape = shape;
			this.payments = payments;
			this.settlement = settlement;
		}

		@GetMapping
		public List<D> list() {
			return payments.list(kind, shape);
		}

		@GetMapping("/{id}")
		public D get(@PathVariable Id id) {
			return payments.get(kind, id.value(), shape);
		}

		@PostMapping
		public ResponseEntity<Map<String, Object>> create(@RequestBody I in) {
			return Responses.createdId(payments.create(kind, in.payment()));
		}

		@PutMapping("/{id}")
		public ResponseEntity<Void> update(@PathVariable Id id, @RequestBody I in) {
			payments.update(kind, id.value(), in.payment());
			return Responses.noContent();
		}

		@DeleteMapping("/{id}")
		public ResponseEntity<Void> delete(@PathVariable Id id) {
			payments.delete(kind, id.value());
			return Responses.noContent();
		}

		@PostMapping("/{id}/post")
		public Map<String, Integer> post(@PathVariable Id id) {
			return Map.of("journal_entry_id", payments.post(kind, id.value()));
		}

		@PostMapping("/{id}/unpost")
		public Map<String, Integer> unpost(@PathVariable Id id) {
			return Map.of("reversal_entry_id", payments.unpost(kind, id.value()));
		}

		@PostMapping("/{id}/apply")
		public Map<String, List<Applied>> apply(@PathVariable Id id) {
			return Map.of("applications", settlement.apply(kind.settler(), id.value()));
		}

		@GetMapping("/{id}/applications")
		public List<Application> applications(@PathVariable Id id) {
			return settlement.applications(kind.settler(), id.value());
		}
	}

	@RestController
	@RequestMapping("/api/customer-payments")
	static class CustomerPayments extends PaymentController<CustomerPaymentInput, CustomerPayment> {
		CustomerPayments(PaymentService payments, SettlementService settlement) {
			super(Kind.CUSTOMER, CustomerPayment.class, payments, settlement);
		}
	}

	@RestController
	@RequestMapping("/api/supplier-payments")
	static class SupplierPayments extends PaymentController<SupplierPaymentInput, SupplierPayment> {
		SupplierPayments(PaymentService payments, SettlementService settlement) {
			super(Kind.SUPPLIER, SupplierPayment.class, payments, settlement);
		}
	}
}
