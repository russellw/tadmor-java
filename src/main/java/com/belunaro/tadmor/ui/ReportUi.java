package com.belunaro.tadmor.ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.service.CatalogService;
import com.belunaro.tadmor.service.LedgerService;
import com.belunaro.tadmor.service.LedgerService.ActivityRow;
import com.belunaro.tadmor.service.LedgerService.CashFlowRow;
import com.belunaro.tadmor.service.SettingsService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The reports (spec/domain.md §13.8, R1 to R8), over the API's report
 * services, with the section totals and identities the screens show worked
 * out here in exact decimals. Every date bound is optional; a malformed one
 * is shown as an error rather than ignored.
 */
@Controller
public class ReportUi {

	private final LedgerService ledger;
	private final CatalogService catalog;
	private final SettingsService settings;

	public ReportUi(LedgerService ledger, CatalogService catalog, SettingsService settings) {
		this.ledger = ledger;
		this.catalog = catalog;
		this.settings = settings;
	}

	private static BigDecimal dec(String s) {
		return s == null ? BigDecimal.ZERO : new BigDecimal(s);
	}

	private static <T> String total(List<T> rows, Function<T, String> amount) {
		return rows.stream().map(r -> dec(amount.apply(r))).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString();
	}

	/** A date bound: blank is unbounded; a malformed one is an error on the model. */
	private static LocalDate bound(String value, String name, Model model, HttpServletResponse response) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value.strip());
		} catch (DateTimeParseException e) {
			model.addAttribute("error", name + " must be a date (YYYY-MM-DD)");
			response.setStatus(400);
			return null;
		}
	}

	/** A titled group of rows and their total. */
	public record Section<T>(String title, List<T> rows, String total) {
	}

	/** R1. */
	@GetMapping("/reports/profit-and-loss")
	public String profitAndLoss(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			Model model, HttpServletResponse response) {
		LocalDate f = bound(from, "From", model, response);
		LocalDate t = bound(to, "To", model, response);
		List<ActivityRow> rows = ledger.profitAndLoss(f, t);
		List<ActivityRow> revenue = rows.stream().filter(r -> r.accountType().equals("revenue")).toList();
		List<ActivityRow> expense = rows.stream().filter(r -> r.accountType().equals("expense")).toList();
		String revenueTotal = total(revenue, ActivityRow::amount);
		String expenseTotal = total(expense, ActivityRow::amount);
		model.addAttribute("sections", List.of(new Section<>("Revenue", revenue, revenueTotal),
				new Section<>("Expenses", expense, expenseTotal)));
		model.addAttribute("netIncome", dec(revenueTotal).subtract(dec(expenseTotal)).toPlainString());
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		return "report-pnl";
	}

	/** R2: assets = liabilities + equity + current earnings, visibly. */
	@GetMapping("/reports/balance-sheet")
	public String balanceSheet(@RequestParam(name = "as_of", required = false) String asOf, Model model,
			HttpServletResponse response) {
		LedgerService.BalanceSheet bs = ledger.balanceSheet(bound(asOf, "As of", model, response));
		List<Section<ActivityRow>> sections = new ArrayList<>();
		for (String[] type : new String[][] { { "asset", "Assets" }, { "liability", "Liabilities" }, { "equity", "Equity" } }) {
			List<ActivityRow> rows = bs.rows().stream().filter(r -> r.accountType().equals(type[0])).toList();
			sections.add(new Section<>(type[1], rows, total(rows, ActivityRow::amount)));
		}
		model.addAttribute("sections", sections);
		model.addAttribute("currentEarnings", bs.currentEarnings());
		model.addAttribute("claims", dec(sections.get(1).total()).add(dec(sections.get(2).total()))
				.add(dec(bs.currentEarnings())).toPlainString());
		model.addAttribute("asOf", asOf);
		return "report-bs";
	}

	/** R3: operating starts from net income; then investing and financing; then cash. */
	@GetMapping("/reports/cash-flow")
	public String cashFlow(@RequestParam(required = false) String from, @RequestParam(required = false) String to, Model model,
			HttpServletResponse response) {
		LedgerService.CashFlow cf = ledger.cashFlow(bound(from, "From", model, response), bound(to, "To", model, response));
		List<Section<CashFlowRow>> sections = new ArrayList<>();
		for (String activity : new String[] { "operating", "investing", "financing" }) {
			List<CashFlowRow> rows = cf.rows().stream().filter(r -> r.activity().equals(activity)).toList();
			String subtotal = total(rows, CashFlowRow::amount);
			if (activity.equals("operating")) {
				subtotal = dec(subtotal).add(dec(cf.netIncome())).toPlainString();
			}
			sections.add(new Section<>(Character.toUpperCase(activity.charAt(0)) + activity.substring(1) + " activities", rows,
					subtotal));
		}
		model.addAttribute("cf", cf);
		model.addAttribute("sections", sections);
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		return "report-cf";
	}

	/** R4: every account, each linking to its ledger. */
	@GetMapping("/reports/trial-balance")
	public String trialBalance(Model model) {
		List<LedgerService.TrialBalanceRow> rows = ledger.trialBalance();
		model.addAttribute("rows", rows);
		model.addAttribute("totalDebit", total(rows, LedgerService.TrialBalanceRow::totalDebit));
		model.addAttribute("totalCredit", total(rows, LedgerService.TrialBalanceRow::totalCredit));
		model.addAttribute("totalBalance", total(rows, LedgerService.TrialBalanceRow::balance));
		return "report-tb";
	}

	/** A ledger line with the running base balance after it. */
	public record LedgerLine(LedgerService.LedgerRow row, String balance, boolean foreign) {
	}

	/** R5: running balance in base; foreign lines show their currency and base amounts too. */
	@GetMapping("/accounts/{id}/ledger")
	public String accountLedger(@PathVariable int id, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, Model model, HttpServletResponse response) {
		CatalogService.Account account = catalog.account(id);
		String base = settings.settings().baseCurrency();
		BigDecimal running = BigDecimal.ZERO;
		List<LedgerLine> lines = new ArrayList<>();
		for (LedgerService.LedgerRow r : ledger.accountLedger(id, bound(from, "From", model, response),
				bound(to, "To", model, response))) {
			running = running.add(dec(r.baseDebit())).subtract(dec(r.baseCredit()));
			lines.add(new LedgerLine(r, running.toPlainString(), !r.currencyCode().equals(base)));
		}
		model.addAttribute("account", account);
		model.addAttribute("lines", lines);
		model.addAttribute("anyForeign", lines.stream().anyMatch(LedgerLine::foreign));
		model.addAttribute("base", base);
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		return "report-ledger";
	}

	/** R6. */
	@GetMapping("/journal-entries/{id}")
	public String journalEntry(@PathVariable int id, Model model) {
		LedgerService.JournalEntry e = ledger.journalEntry(id);
		model.addAttribute("e", e);
		model.addAttribute("base", settings.settings().baseCurrency());
		Map<String, String> totals = new LinkedHashMap<>();
		totals.put("debit", total(e.lines(), LedgerService.JournalLine::debit));
		totals.put("credit", total(e.lines(), LedgerService.JournalLine::credit));
		totals.put("baseDebit", total(e.lines(), LedgerService.JournalLine::baseDebit));
		totals.put("baseCredit", total(e.lines(), LedgerService.JournalLine::baseCredit));
		model.addAttribute("totals", totals);
		return "journal-entry";
	}

	/** R7. */
	@GetMapping("/reports/{side:ar|ap}-aging")
	public String aging(@PathVariable String side, Model model) {
		boolean ar = side.equals("ar");
		List<LedgerService.AgingRow> rows = ar ? ledger.receivablesAging() : ledger.payablesAging();
		model.addAttribute("title", ar ? "AR aging" : "AP aging");
		model.addAttribute("partyLabel", ar ? "Customer" : "Supplier");
		model.addAttribute("partyPath", ar ? "customers" : "suppliers");
		model.addAttribute("rows", rows);
		model.addAttribute("totals", List.of(total(rows, LedgerService.AgingRow::notYetDue),
				total(rows, LedgerService.AgingRow::days130), total(rows, LedgerService.AgingRow::days3160),
				total(rows, LedgerService.AgingRow::days6190), total(rows, LedgerService.AgingRow::daysOver90),
				total(rows, LedgerService.AgingRow::totalOutstanding)));
		return "report-aging";
	}

	/** R8. */
	@GetMapping("/reports/inventory-valuation")
	public String valuation(Model model) {
		List<LedgerService.ValuationRow> rows = ledger.inventoryValuation();
		model.addAttribute("rows", rows);
		model.addAttribute("totalValue", total(rows, LedgerService.ValuationRow::valueOnHand));
		return "report-valuation";
	}
}
