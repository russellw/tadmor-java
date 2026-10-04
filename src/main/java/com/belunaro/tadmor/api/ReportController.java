package com.belunaro.tadmor.api;

import java.util.List;

import com.belunaro.tadmor.service.LedgerService;
import com.belunaro.tadmor.service.LedgerService.JournalEntry;
import com.belunaro.tadmor.service.LedgerService.TrialBalanceRow;
import com.belunaro.tadmor.service.LedgerService.ValuationRow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The journal and reports (spec/api.md §5.14). */
@RestController
@RequestMapping("/api")
public class ReportController {

	private final LedgerService ledger;

	public ReportController(LedgerService ledger) {
		this.ledger = ledger;
	}

	@GetMapping("/journal-entries/{id}")
	public JournalEntry journalEntry(@PathVariable Id id) {
		return ledger.journalEntry(id.value());
	}

	@GetMapping("/inventory-valuation")
	public List<ValuationRow> inventoryValuation() {
		return ledger.inventoryValuation();
	}

	@GetMapping("/trial-balance")
	public List<TrialBalanceRow> trialBalance() {
		return ledger.trialBalance();
	}
}
