package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.BankService;
import com.belunaro.tadmor.service.BankService.BankStatement;
import com.belunaro.tadmor.service.BankService.LineInput;
import com.belunaro.tadmor.service.BankService.MatchCandidate;
import com.belunaro.tadmor.service.BankService.StatementInput;
import com.belunaro.tadmor.service.BankService.StatementLine;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bank reconciliation (spec/api.md §5.13). Reopening is for administrators only (SecurityConfig). */
@RestController
@RequestMapping("/api")
public class BankController {

	private final BankService bank;

	public BankController(BankService bank) {
		this.bank = bank;
	}

	@GetMapping("/bank-statements")
	public List<BankStatement> statements() {
		return bank.statements();
	}

	@GetMapping("/bank-statements/{id}")
	public BankStatement statement(@PathVariable Id id) {
		return bank.statement(id.value());
	}

	@PostMapping("/bank-statements")
	public ResponseEntity<Map<String, Object>> createStatement(@RequestBody StatementInput in) {
		return Responses.createdId(bank.createStatement(in));
	}

	@PutMapping("/bank-statements/{id}")
	public ResponseEntity<Void> updateStatement(@PathVariable Id id, @RequestBody StatementInput in) {
		bank.updateStatement(id.value(), in);
		return Responses.noContent();
	}

	@DeleteMapping("/bank-statements/{id}")
	public ResponseEntity<Void> deleteStatement(@PathVariable Id id) {
		bank.deleteStatement(id.value());
		return Responses.noContent();
	}

	@GetMapping("/bank-statements/{id}/lines")
	public List<StatementLine> lines(@PathVariable Id id) {
		return bank.lines(id.value());
	}

	@PostMapping("/bank-statements/{id}/lines")
	public ResponseEntity<Map<String, Object>> addLine(@PathVariable Id id, @RequestBody LineInput in) {
		return Responses.createdId(bank.addLine(id.value(), in));
	}

	public record ImportRequest(String csv) {
	}

	@PostMapping("/bank-statements/{id}/import")
	public Map<String, Integer> importCsv(@PathVariable Id id, @RequestBody ImportRequest in) {
		return Map.of("imported", bank.importCsv(id.value(), in.csv()));
	}

	@GetMapping("/bank-statements/{id}/candidates")
	public List<MatchCandidate> candidates(@PathVariable Id id) {
		return bank.candidates(id.value());
	}

	@PostMapping("/bank-statements/{id}/auto-match")
	public Map<String, Integer> autoMatch(@PathVariable Id id) {
		return Map.of("matched", bank.autoMatch(id.value()));
	}

	@PostMapping("/bank-statements/{id}/reconcile")
	public ResponseEntity<Void> reconcile(@PathVariable Id id) {
		bank.reconcile(id.value());
		return Responses.noContent();
	}

	@PostMapping("/bank-statements/{id}/reopen")
	public ResponseEntity<Void> reopen(@PathVariable Id id) {
		bank.reopen(id.value());
		return Responses.noContent();
	}

	public record MatchRequest(Integer journalLineId) {
	}

	@PostMapping("/bank-statement-lines/{id}/match")
	public ResponseEntity<Void> match(@PathVariable Id id, @RequestBody(required = false) MatchRequest in) {
		bank.match(id.value(), in == null ? null : in.journalLineId());
		return Responses.noContent();
	}

	@PostMapping("/bank-statement-lines/{id}/unmatch")
	public ResponseEntity<Void> unmatch(@PathVariable Id id) {
		bank.unmatch(id.value());
		return Responses.noContent();
	}

	@DeleteMapping("/bank-statement-lines/{id}")
	public ResponseEntity<Void> deleteLine(@PathVariable Id id) {
		bank.deleteLine(id.value());
		return Responses.noContent();
	}
}
