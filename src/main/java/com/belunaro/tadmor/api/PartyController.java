package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.PartyService;
import com.belunaro.tadmor.service.PartyService.Customer;
import com.belunaro.tadmor.service.PartyService.CustomerInput;
import com.belunaro.tadmor.service.PartyService.Organization;
import com.belunaro.tadmor.service.PartyService.OrganizationInput;
import com.belunaro.tadmor.service.PartyService.Supplier;
import com.belunaro.tadmor.service.PartyService.SupplierInput;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organizations, customers, and suppliers (spec/api.md §5.2, §5.3). */
@RestController
@RequestMapping("/api")
public class PartyController {

	private final PartyService parties;

	public PartyController(PartyService parties) {
		this.parties = parties;
	}

	@GetMapping("/organizations")
	public List<Organization> organizations() {
		return parties.organizations();
	}

	@GetMapping("/organizations/{id}")
	public Organization organization(@PathVariable Id id) {
		return parties.organization(id.value());
	}

	@PostMapping("/organizations")
	public ResponseEntity<Map<String, Object>> createOrganization(@RequestBody OrganizationInput in) {
		return Responses.createdId(parties.createOrganization(in));
	}

	@PutMapping("/organizations/{id}")
	public ResponseEntity<Void> updateOrganization(@PathVariable Id id, @RequestBody OrganizationInput in) {
		parties.updateOrganization(id.value(), in);
		return Responses.noContent();
	}

	@GetMapping("/customers")
	public List<Customer> customers() {
		return parties.customers();
	}

	@GetMapping("/customers/{id}")
	public Customer customer(@PathVariable Id id) {
		return parties.customer(id.value());
	}

	@PostMapping("/customers")
	public ResponseEntity<Map<String, Object>> createCustomer(@RequestBody CustomerInput in) {
		return Responses.createdId(parties.createCustomer(in));
	}

	@PutMapping("/customers/{id}")
	public ResponseEntity<Void> updateCustomer(@PathVariable Id id, @RequestBody CustomerInput in) {
		parties.updateCustomer(id.value(), in);
		return Responses.noContent();
	}

	@GetMapping("/suppliers")
	public List<Supplier> suppliers() {
		return parties.suppliers();
	}

	@GetMapping("/suppliers/{id}")
	public Supplier supplier(@PathVariable Id id) {
		return parties.supplier(id.value());
	}

	@PostMapping("/suppliers")
	public ResponseEntity<Map<String, Object>> createSupplier(@RequestBody SupplierInput in) {
		return Responses.createdId(parties.createSupplier(in));
	}

	@PutMapping("/suppliers/{id}")
	public ResponseEntity<Void> updateSupplier(@PathVariable Id id, @RequestBody SupplierInput in) {
		parties.updateSupplier(id.value(), in);
		return Responses.noContent();
	}
}
