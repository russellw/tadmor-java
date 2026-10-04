package com.belunaro.tadmor.ui;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.service.BankService;
import com.belunaro.tadmor.service.CalendarService;
import com.belunaro.tadmor.service.CalendarService.AccountingPeriod;
import com.belunaro.tadmor.service.CalendarService.AccountingPeriodInput;
import com.belunaro.tadmor.service.CalendarService.FiscalYear;
import com.belunaro.tadmor.service.CalendarService.FiscalYearInput;
import com.belunaro.tadmor.service.YearEndService;
import com.belunaro.tadmor.ui.Resource.Field;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Accounting screens (spec/domain.md §13.9): fiscal years and periods (A1),
 * year-end close and reopen (A2, administrators), and bank statements with
 * reconciliation (A4, A5). Exchange rates (A3) are a generic resource.
 */
@Controller
public class AccountingUi {

	private static final Resource YEAR_FORM = Resource.form("fiscal year", List.of(
			Field.required("name", "Name", "text"), Field.required("start_date", "Start", "date"),
			Field.required("end_date", "End", "date")));

	private static final Resource PERIOD_FORM = Resource.form("accounting period", List.of(
			Field.select("fiscal_year_id", "Fiscal year", "fiscal-years").requiredField(), Field.required("name", "Name", "text"),
			Field.required("start_date", "Start", "date"), Field.required("end_date", "End", "date")));

	private static final Resource STATEMENT_FORM = Resource.form("bank statement", List.of(
			Field.select("account_id", "Account", "cash-accounts").requiredField(),
			Field.required("statement_date", "Statement date", "date"),
			Field.required("opening_balance", "Opening balance", "decimal"),
			Field.required("closing_balance", "Closing balance", "decimal"), Field.of("reference", "Reference", "text")));

	private final Ui ui;
	private final CalendarService calendar;
	private final YearEndService yearEnd;
	private final BankService bank;
	private final JdbcClient jdbc;

	public AccountingUi(Ui ui, CalendarService calendar, YearEndService yearEnd, BankService bank, JdbcClient jdbc) {
		this.ui = ui;
		this.calendar = calendar;
		this.yearEnd = yearEnd;
		this.bank = bank;
		this.jdbc = jdbc;
	}

	// ---- A1: fiscal years and periods ----

	/** A fiscal year with its periods. */
	public record Year(FiscalYear year, List<AccountingPeriod> periods, boolean reopenable) {
	}

	@GetMapping("/periods")
	public String periods(Model model, HttpServletResponse response) {
		return periodsPage(model, response, null);
	}

	private String periodsPage(Model model, HttpServletResponse response, Ui.Problem problem) {
		List<FiscalYear> years = calendar.fiscalYears();
		List<AccountingPeriod> periods = calendar.periods();
		// Only the latest closed year can be reopened (domain §9.3).
		Optional<FiscalYear> latestClosed = years.stream().filter(y -> y.status().equals("closed"))
				.max(Comparator.comparing(FiscalYear::startDate));
		model.addAttribute("years", years.stream().map(y -> new Year(y,
				periods.stream().filter(p -> p.fiscalYearId() == y.id()).toList(),
				latestClosed.map(l -> l.id() == y.id()).orElse(false))).toList());
		model.addAttribute("error", problem == null ? null : problem.message());
		model.addAttribute("notice", null);
		if (problem != null) {
			response.setStatus(problem.status().value());
		}
		return "periods";
	}

	@GetMapping("/fiscal-years/new")
	public String newYear(Model model, HttpServletResponse response) {
		return FormPage.render(model, YEAR_FORM, "New fiscal year", "/fiscal-years/new", "/periods", new HashMap<>(), true,
				null, response);
	}

	@PostMapping("/fiscal-years/new")
	public String createYear(@RequestParam Map<String, String> submitted, Model model, HttpServletResponse response) {
		Map<String, Object> values = FormPage.values(YEAR_FORM, submitted);
		var result = ui.attempt(() -> calendar.createFiscalYear(ui.bind(values, FiscalYearInput.class)));
		return result.ok() ? "redirect:/periods"
				: FormPage.render(model, YEAR_FORM, "New fiscal year", "/fiscal-years/new", "/periods", values, true,
						result.problem(), response);
	}

	@GetMapping("/fiscal-years/{id}/edit")
	public String editYear(@PathVariable int id, Model model, HttpServletResponse response) {
		return FormPage.render(model, YEAR_FORM, "Edit fiscal year", "/fiscal-years/" + id + "/edit", "/periods",
				ui.map(calendar.fiscalYear(id)), false, null, response);
	}

	@PostMapping("/fiscal-years/{id}/edit")
	public String updateYear(@PathVariable int id, @RequestParam Map<String, String> submitted, Model model,
			HttpServletResponse response) {
		calendar.fiscalYear(id);
		Map<String, Object> values = FormPage.values(YEAR_FORM, submitted);
		var result = ui.attempt(() -> calendar.updateFiscalYear(id, ui.bind(values, FiscalYearInput.class)));
		return result.ok() ? "redirect:/periods"
				: FormPage.render(model, YEAR_FORM, "Edit fiscal year", "/fiscal-years/" + id + "/edit", "/periods", values,
						false, result.problem(), response);
	}

	/** The new-period form proposes the month after the latest existing period, in the year covering it. */
	@GetMapping("/accounting-periods/new")
	public String newPeriod(Model model, HttpServletResponse response) {
		Map<String, Object> values = new HashMap<>();
		Optional<AccountingPeriod> latest = calendar.periods().stream().max(Comparator.comparing(AccountingPeriod::endDate));
		if (latest.isPresent()) {
			LocalDate start = LocalDate.parse(latest.get().endDate()).plusDays(1);
			YearMonth month = YearMonth.from(start);
			LocalDate end = month.atEndOfMonth();
			values.put("name", month.toString());
			values.put("start_date", start.toString());
			values.put("end_date", end.toString());
			calendar.fiscalYears().stream()
					.filter(y -> !LocalDate.parse(y.startDate()).isAfter(start) && !LocalDate.parse(y.endDate()).isBefore(start))
					.findFirst().ifPresent(y -> {
						values.put("fiscal_year_id", y.id());
						if (LocalDate.parse(y.endDate()).isBefore(end)) {
							values.put("end_date", y.endDate());
						}
					});
		}
		return FormPage.render(model, PERIOD_FORM, "New accounting period", "/accounting-periods/new", "/periods", values, true,
				null, response);
	}

	@PostMapping("/accounting-periods/new")
	public String createPeriod(@RequestParam Map<String, String> submitted, Model model, HttpServletResponse response) {
		Map<String, Object> values = FormPage.values(PERIOD_FORM, submitted);
		var result = ui.attempt(() -> calendar.createPeriod(ui.bind(values, AccountingPeriodInput.class)));
		return result.ok() ? "redirect:/periods"
				: FormPage.render(model, PERIOD_FORM, "New accounting period", "/accounting-periods/new", "/periods", values,
						true, result.problem(), response);
	}

	@GetMapping("/accounting-periods/{id}/edit")
	public String editPeriod(@PathVariable int id, Model model, HttpServletResponse response) {
		return FormPage.render(model, PERIOD_FORM, "Edit accounting period", "/accounting-periods/" + id + "/edit", "/periods",
				ui.map(calendar.period(id)), false, null, response);
	}

	@PostMapping("/accounting-periods/{id}/edit")
	public String updatePeriod(@PathVariable int id, @RequestParam Map<String, String> submitted, Model model,
			HttpServletResponse response) {
		AccountingPeriod current = calendar.period(id);
		Map<String, Object> values = FormPage.values(PERIOD_FORM, submitted);
		values.put("status", current.status()); // the edit form keeps the status; toggling is its own action
		var result = ui.attempt(() -> calendar.updatePeriod(id, ui.bind(values, AccountingPeriodInput.class)));
		return result.ok() ? "redirect:/periods"
				: FormPage.render(model, PERIOD_FORM, "Edit accounting period", "/accounting-periods/" + id + "/edit",
						"/periods", values, false, result.problem(), response);
	}

	/** Closes an open period, or reopens a closed one, in one step. */
	@PostMapping("/accounting-periods/{id}/toggle")
	public String togglePeriod(@PathVariable int id, Model model, HttpServletResponse response) {
		AccountingPeriod p = calendar.period(id);
		var result = ui.attempt(() -> calendar.updatePeriod(id, new AccountingPeriodInput(p.fiscalYearId(), p.name(),
				p.startDate(), p.endDate(), p.status().equals("open") ? "closed" : "open")));
		return result.ok() ? "redirect:/periods" : periodsPage(model, response, result.problem());
	}

	// ---- A2: year-end (administrators only, SecurityConfig) ----

	@GetMapping("/year-end/{id}/close")
	public String closeForm(@PathVariable int id, Model model, HttpServletResponse response) {
		return closePage(model, response, id, null, null);
	}

	private String closePage(Model model, HttpServletResponse response, int id, String chosen, Ui.Problem problem) {
		FiscalYear year = calendar.fiscalYear(id);
		model.addAttribute("year", year);
		// The seeded Retained Earnings account (3000) is proposed.
		model.addAttribute("retained", chosen != null ? chosen
				: jdbc.sql("SELECT id::text FROM accounts WHERE code = '3000'").query(String.class).optional().orElse(null));
		model.addAttribute("nextName", "FY" + LocalDate.parse(year.endDate()).plusYears(1).getYear());
		model.addAttribute("error", problem == null ? null : problem.message());
		if (problem != null) {
			response.setStatus(problem.status().value());
		}
		return "year-close";
	}

	@PostMapping("/year-end/{id}/close")
	public String close(@PathVariable int id, @RequestParam(name = "retained_earnings_account_id", required = false) Integer retained,
			Model model, HttpServletResponse response) {
		var result = ui.attempt(() -> yearEnd.close(id, retained));
		return result.ok() ? "redirect:/periods"
				: closePage(model, response, id, retained == null ? null : retained.toString(), result.problem());
	}

	@PostMapping("/year-end/{id}/reopen")
	public String reopen(@PathVariable int id, Model model, HttpServletResponse response) {
		var result = ui.attempt(() -> yearEnd.reopen(id));
		return result.ok() ? "redirect:/periods" : periodsPage(model, response, result.problem());
	}

	// ---- A4, A5: bank statements ----

	@GetMapping("/bank-statements")
	public String statements(Model model) {
		model.addAttribute("rows", bank.statements());
		return "statement-list";
	}

	@GetMapping("/bank-statements/new")
	public String newStatement(Model model, HttpServletResponse response) {
		return FormPage.render(model, STATEMENT_FORM, "New bank statement", "/bank-statements/new", "/bank-statements",
				new HashMap<>(Map.of("opening_balance", "0")), true, null, response);
	}

	@PostMapping("/bank-statements/new")
	public String createStatement(@RequestParam Map<String, String> submitted, Model model, HttpServletResponse response) {
		Map<String, Object> values = FormPage.values(STATEMENT_FORM, submitted);
		var result = ui.attempt(() -> bank.createStatement(ui.bind(values, BankService.StatementInput.class)));
		return result.ok() ? "redirect:/bank-statements/" + result.value()
				: FormPage.render(model, STATEMENT_FORM, "New bank statement", "/bank-statements/new", "/bank-statements",
						values, true, result.problem(), response);
	}

	@GetMapping("/bank-statements/{id}/edit")
	public String editStatement(@PathVariable int id, Model model, HttpServletResponse response) {
		return FormPage.render(model, STATEMENT_FORM, "Edit bank statement", "/bank-statements/" + id + "/edit",
				"/bank-statements/" + id, ui.map(bank.statement(id)), false, null, response);
	}

	@PostMapping("/bank-statements/{id}/edit")
	public String updateStatement(@PathVariable int id, @RequestParam Map<String, String> submitted, Model model,
			HttpServletResponse response) {
		bank.statement(id);
		Map<String, Object> values = FormPage.values(STATEMENT_FORM, submitted);
		var result = ui.attempt(() -> bank.updateStatement(id, ui.bind(values, BankService.StatementInput.class)));
		return result.ok() ? "redirect:/bank-statements/" + id
				: FormPage.render(model, STATEMENT_FORM, "Edit bank statement", "/bank-statements/" + id + "/edit",
						"/bank-statements/" + id, values, false, result.problem(), response);
	}

	/** A5. With {@code all}, every unmatched line offers every candidate, not just those of its amount. */
	@GetMapping("/bank-statements/{id}")
	public String statement(@PathVariable int id, @RequestParam(defaultValue = "false") boolean all, Model model,
			HttpServletResponse response) {
		return statementPage(model, response, id, all, null, null);
	}

	private String statementPage(Model model, HttpServletResponse response, int id, boolean all, Ui.Problem problem,
			String notice) {
		List<BankService.StatementLine> lines = bank.lines(id);
		List<BankService.MatchCandidate> candidates = bank.candidates(id);
		// Each unmatched line offers the candidates of its amount, or every candidate when asked.
		Map<Integer, List<BankService.MatchCandidate>> offers = new HashMap<>();
		for (BankService.StatementLine l : lines) {
			if (l.journalLineId() == null) {
				offers.put(l.id(), all ? candidates : candidates.stream()
						.filter(c -> new java.math.BigDecimal(c.amount()).compareTo(new java.math.BigDecimal(l.amount())) == 0)
						.toList());
			}
		}
		model.addAttribute("s", bank.statement(id));
		model.addAttribute("lines", lines);
		model.addAttribute("offers", offers);
		model.addAttribute("all", all);
		model.addAttribute("error", problem == null ? null : problem.message());
		model.addAttribute("notice", notice);
		if (problem != null) {
			response.setStatus(problem.status().value());
		}
		return "statement-detail";
	}

	private String act(Model model, HttpServletResponse response, int statement, Runnable action) {
		var result = ui.attempt(action);
		return result.ok() ? "redirect:/bank-statements/" + statement
				: statementPage(model, response, statement, false, result.problem(), null);
	}

	@PostMapping("/bank-statements/{id}/lines")
	public String addLine(@PathVariable int id, @RequestParam Map<String, String> form, Model model,
			HttpServletResponse response) {
		bank.statement(id);
		return act(model, response, id, () -> bank.addLine(id, new BankService.LineInput(form.get("txn_date"),
				form.get("description"), Ui.value(form, "reference"), form.get("amount"))));
	}

	@PostMapping("/bank-statements/{id}/import")
	public String importCsv(@PathVariable int id, @RequestParam(defaultValue = "") String csv, Model model,
			HttpServletResponse response) {
		bank.statement(id);
		var result = ui.attempt(() -> bank.importCsv(id, csv));
		return result.ok() ? statementPage(model, response, id, false, null, "Imported " + result.value() + " lines.")
				: statementPage(model, response, id, false, result.problem(), null);
	}

	@PostMapping("/bank-statements/{id}/auto-match")
	public String autoMatch(@PathVariable int id, Model model, HttpServletResponse response) {
		bank.statement(id);
		var result = ui.attempt(() -> bank.autoMatch(id));
		return result.ok() ? statementPage(model, response, id, false, null, "Matched " + result.value() + " lines.")
				: statementPage(model, response, id, false, result.problem(), null);
	}

	@PostMapping("/bank-statements/{id}/reconcile")
	public String reconcile(@PathVariable int id, Model model, HttpServletResponse response) {
		bank.statement(id);
		return act(model, response, id, () -> bank.reconcile(id));
	}

	/** Administrators only (SecurityConfig). */
	@PostMapping("/bank-statements/{id}/reopen")
	public String reopenStatement(@PathVariable int id, Model model, HttpServletResponse response) {
		bank.statement(id);
		return act(model, response, id, () -> bank.reopen(id));
	}

	@PostMapping("/bank-statements/{id}/delete")
	public String deleteStatement(@PathVariable int id, Model model, HttpServletResponse response) {
		bank.statement(id);
		var result = ui.attempt(() -> bank.deleteStatement(id));
		return result.ok() ? "redirect:/bank-statements" : statementPage(model, response, id, false, result.problem(), null);
	}

	@PostMapping("/bank-statements/{id}/lines/{line}/match")
	public String match(@PathVariable int id, @PathVariable int line,
			@RequestParam(name = "journal_line_id", required = false) Integer journalLine, Model model,
			HttpServletResponse response) {
		requireLine(id, line);
		return act(model, response, id, () -> bank.match(line, journalLine));
	}

	@PostMapping("/bank-statements/{id}/lines/{line}/unmatch")
	public String unmatch(@PathVariable int id, @PathVariable int line, Model model, HttpServletResponse response) {
		requireLine(id, line);
		return act(model, response, id, () -> bank.unmatch(line));
	}

	@PostMapping("/bank-statements/{id}/lines/{line}/delete")
	public String deleteLine(@PathVariable int id, @PathVariable int line, Model model, HttpServletResponse response) {
		requireLine(id, line);
		return act(model, response, id, () -> bank.deleteLine(line));
	}

	/** 404 unless the line is on the statement in the path. */
	private void requireLine(int statement, int line) {
		if (bank.lines(statement).stream().noneMatch(l -> l.id() == line)) {
			throw com.belunaro.tadmor.service.ServiceException.notFound();
		}
	}
}
