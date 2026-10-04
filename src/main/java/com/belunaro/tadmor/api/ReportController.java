package com.belunaro.tadmor.api;

import java.time.LocalDate;
import java.util.List;

import com.belunaro.tadmor.service.LedgerService;
import com.belunaro.tadmor.service.LedgerService.ActivityRow;
import com.belunaro.tadmor.service.LedgerService.AgingRow;
import com.belunaro.tadmor.service.LedgerService.BalanceSheet;
import com.belunaro.tadmor.service.LedgerService.CashFlow;
import com.belunaro.tadmor.service.LedgerService.JournalEntry;
import com.belunaro.tadmor.service.LedgerService.TrialBalanceRow;
import com.belunaro.tadmor.service.LedgerService.ValuationRow;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	// A malformed date parameter is a 400; an absent one is unbounded.

	@GetMapping("/profit-and-loss")
	public List<ActivityRow> profitAndLoss(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		return ledger.profitAndLoss(from, to);
	}

	@GetMapping("/balance-sheet")
	public BalanceSheet balanceSheet(
			@RequestParam(name = "as_of", required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf) {
		return ledger.balanceSheet(asOf);
	}

	@GetMapping("/cash-flow")
	public CashFlow cashFlow(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		return ledger.cashFlow(from, to);
	}

	@GetMapping("/ar-aging")
	public List<AgingRow> receivablesAging() {
		return ledger.receivablesAging();
	}

	@GetMapping("/ap-aging")
	public List<AgingRow> payablesAging() {
		return ledger.payablesAging();
	}
}
