package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.StockService;
import com.belunaro.tadmor.service.StockService.MovementInput;
import com.belunaro.tadmor.service.StockService.StockMovement;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stock movements (spec/api.md §5.12). Unposting is for administrators only (SecurityConfig). */
@RestController
@RequestMapping("/api/stock-movements")
public class StockController {

	private final StockService stock;

	public StockController(StockService stock) {
		this.stock = stock;
	}

	@GetMapping
	public List<StockMovement> list() {
		return stock.list();
	}

	@GetMapping("/{id}")
	public StockMovement get(@PathVariable Id id) {
		return stock.get(id.value());
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@RequestBody MovementInput in) {
		return Responses.createdId(stock.create(in));
	}

	@PutMapping("/{id}")
	public ResponseEntity<Void> update(@PathVariable Id id, @RequestBody MovementInput in) {
		stock.update(id.value(), in);
		return Responses.noContent();
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Id id) {
		stock.delete(id.value());
		return Responses.noContent();
	}

	/** The credit account, needed only for receipts. */
	public record PostRequest(Integer creditAccountId) {
	}

	/** The body may be absent. */
	@PostMapping("/{id}/post")
	public Map<String, Integer> post(@PathVariable Id id, @RequestBody(required = false) PostRequest in) {
		return Map.of("journal_entry_id", stock.post(id.value(), in == null ? null : in.creditAccountId()));
	}

	@PostMapping("/{id}/unpost")
	public Map<String, Integer> unpost(@PathVariable Id id) {
		return Map.of("reversal_entry_id", stock.unpost(id.value()));
	}
}
