package com.belunaro.tadmor.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.CatalogService;
import com.belunaro.tadmor.service.CatalogService.Account;
import com.belunaro.tadmor.service.CatalogService.AccountInput;
import com.belunaro.tadmor.service.CatalogService.PaymentTerm;
import com.belunaro.tadmor.service.CatalogService.PaymentTermInput;
import com.belunaro.tadmor.service.CatalogService.Product;
import com.belunaro.tadmor.service.CatalogService.ProductInput;
import com.belunaro.tadmor.service.CatalogService.TaxCode;
import com.belunaro.tadmor.service.CatalogService.TaxCodeInput;
import com.belunaro.tadmor.service.CatalogService.Warehouse;
import com.belunaro.tadmor.service.CatalogService.WarehouseInput;
import com.belunaro.tadmor.service.LedgerService;
import com.belunaro.tadmor.service.LedgerService.LedgerRow;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Products, the chart of accounts, tax codes, payment terms, and warehouses
 * (spec/api.md §5.4 to §5.6), and an account's ledger (§5.14).
 */
@RestController
@RequestMapping("/api")
public class CatalogController {

	private final CatalogService catalog;
	private final LedgerService ledger;

	public CatalogController(CatalogService catalog, LedgerService ledger) {
		this.catalog = catalog;
		this.ledger = ledger;
	}

	@GetMapping("/products")
	public List<Product> products() {
		return catalog.products();
	}

	@GetMapping("/products/{id}")
	public Product product(@PathVariable Id id) {
		return catalog.product(id.value());
	}

	@PostMapping("/products")
	public ResponseEntity<Map<String, Object>> createProduct(@RequestBody ProductInput in) {
		return Responses.createdId(catalog.createProduct(in));
	}

	@PutMapping("/products/{id}")
	public ResponseEntity<Void> updateProduct(@PathVariable Id id, @RequestBody ProductInput in) {
		catalog.updateProduct(id.value(), in);
		return Responses.noContent();
	}

	@GetMapping("/accounts")
	public List<Account> accounts() {
		return catalog.accounts();
	}

	@GetMapping("/accounts/{id}")
	public Account account(@PathVariable Id id) {
		return catalog.account(id.value());
	}

	@PostMapping("/accounts")
	public ResponseEntity<Map<String, Object>> createAccount(@RequestBody AccountInput in) {
		return Responses.createdId(catalog.createAccount(in));
	}

	@PutMapping("/accounts/{id}")
	public ResponseEntity<Void> updateAccount(@PathVariable Id id, @RequestBody AccountInput in) {
		catalog.updateAccount(id.value(), in);
		return Responses.noContent();
	}

	/** A malformed date is a 400, before the account is looked up. */
	@GetMapping("/accounts/{id}/ledger")
	public List<LedgerRow> accountLedger(@PathVariable Id id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ledger.accountLedger(id.value(), from, to);
	}

	@GetMapping("/tax-codes")
	public List<TaxCode> taxCodes() {
		return catalog.taxCodes();
	}

	@GetMapping("/tax-codes/{code}")
	public TaxCode taxCode(@PathVariable String code) {
		return catalog.taxCode(code);
	}

	@PostMapping("/tax-codes")
	public ResponseEntity<Map<String, Object>> createTaxCode(@RequestBody TaxCodeInput in) {
		return Responses.createdCode(catalog.createTaxCode(in));
	}

	@PutMapping("/tax-codes/{code}")
	public ResponseEntity<Void> updateTaxCode(@PathVariable String code, @RequestBody TaxCodeInput in) {
		catalog.updateTaxCode(code, in);
		return Responses.noContent();
	}

	@GetMapping("/payment-terms")
	public List<PaymentTerm> paymentTerms() {
		return catalog.paymentTerms();
	}

	@GetMapping("/payment-terms/{code}")
	public PaymentTerm paymentTerm(@PathVariable String code) {
		return catalog.paymentTerm(code);
	}

	@PostMapping("/payment-terms")
	public ResponseEntity<Map<String, Object>> createPaymentTerm(@RequestBody PaymentTermInput in) {
		return Responses.createdCode(catalog.createPaymentTerm(in));
	}

	@PutMapping("/payment-terms/{code}")
	public ResponseEntity<Void> updatePaymentTerm(@PathVariable String code, @RequestBody PaymentTermInput in) {
		catalog.updatePaymentTerm(code, in);
		return Responses.noContent();
	}

	@GetMapping("/warehouses")
	public List<Warehouse> warehouses() {
		return catalog.warehouses();
	}

	@GetMapping("/warehouses/{id}")
	public Warehouse warehouse(@PathVariable Id id) {
		return catalog.warehouse(id.value());
	}

	@PostMapping("/warehouses")
	public ResponseEntity<Map<String, Object>> createWarehouse(@RequestBody WarehouseInput in) {
		return Responses.createdId(catalog.createWarehouse(in));
	}

	@PutMapping("/warehouses/{id}")
	public ResponseEntity<Void> updateWarehouse(@PathVariable Id id, @RequestBody WarehouseInput in) {
		catalog.updateWarehouse(id.value(), in);
		return Responses.noContent();
	}
}
