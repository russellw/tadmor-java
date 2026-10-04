package com.belunaro.tadmor.ui;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.service.PaymentService;
import com.belunaro.tadmor.service.PaymentService.Kind;
import com.belunaro.tadmor.service.SettingsService;
import com.belunaro.tadmor.service.SettlementService;
import com.belunaro.tadmor.ui.Resource.Field;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Customer and supplier payments (spec/domain.md §13.5, P1 to P4). */
@Controller
public class PaymentUi {

	private static final String C = "/{c:customer-payments|supplier-payments}";

	/** A payment collection's screen. */
	public record Screen(String path, String title, String singular, Kind kind, String partyField, String partyChoices,
			String partyLabel, String accountField, String accountLabel, Class<? extends PaymentService.PaymentRequest> request,
			Class<?> shape, String documents) {
	}

	private static final Screen CUSTOMER = new Screen("customer-payments", "Customer payments", "customer payment",
			Kind.CUSTOMER, "customer_id", "customers", "Customer", "deposit_account_id", "Deposit account",
			PaymentService.CustomerPaymentInput.class, PaymentService.CustomerPayment.class, "sales-invoices");
	private static final Screen SUPPLIER = new Screen("supplier-payments", "Supplier payments", "supplier payment",
			Kind.SUPPLIER, "supplier_id", "suppliers", "Supplier", "payment_account_id", "Payment account",
			PaymentService.SupplierPaymentInput.class, PaymentService.SupplierPayment.class, "purchase-bills");

	private final Ui ui;
	private final PaymentService payments;
	private final SettlementService settlement;
	private final SettingsService settings;
	private final Choices choices;

	public PaymentUi(Ui ui, PaymentService payments, SettlementService settlement, SettingsService settings, Choices choices) {
		this.ui = ui;
		this.payments = payments;
		this.settlement = settlement;
		this.settings = settings;
		this.choices = choices;
	}

	private static Screen screen(String c) {
		return c.equals(CUSTOMER.path()) ? CUSTOMER : SUPPLIER;
	}

	private static Resource form(Screen s) {
		return Resource.form(s.singular(), List.of(
				Field.select(s.partyField(), s.partyLabel(), s.partyChoices()).requiredField(),
				Field.required("payment_date", "Date", "date"),
				Field.select("currency_code", "Currency", "currencies").requiredField(),
				Field.required("amount", "Amount", "decimal"),
				Field.select("method", "Method", "methods"),
				Field.of("reference", "Reference", "text"),
				Field.select(s.accountField(), s.accountLabel(), "postable-accounts")));
	}

	/** P1: newest first. */
	@GetMapping(C)
	public String list(@PathVariable String c, Model model) {
		Screen s = screen(c);
		model.addAttribute("screen", s);
		model.addAttribute("rows", ui.maps(payments.list(s.kind(), s.shape())));
		model.addAttribute("parties", choices.labels(s.partyChoices()));
		return "payment-list";
	}

	/** P2. */
	@GetMapping(C + "/new")
	public String newForm(@PathVariable String c, Model model, HttpServletResponse response) {
		Screen s = screen(c);
		Map<String, Object> values = new HashMap<>();
		values.put("payment_date", LocalDate.now(ZoneOffset.UTC).toString());
		values.put("currency_code", settings.settings().baseCurrency());
		return FormPage.render(model, form(s), "New " + s.singular(), "/" + c + "/new", "/" + c, values, true, null, response);
	}

	@PostMapping(C + "/new")
	public String create(@PathVariable String c, @RequestParam Map<String, String> submitted, Model model,
			HttpServletResponse response) {
		Screen s = screen(c);
		Map<String, Object> values = FormPage.values(form(s), submitted);
		var result = ui.attempt(() -> payments.create(s.kind(), ui.bind(values, s.request()).payment()));
		if (result.ok()) {
			return "redirect:/" + c + "/" + result.value();
		}
		return FormPage.render(model, form(s), "New " + s.singular(), "/" + c + "/new", "/" + c, values, true,
				result.problem(), response);
	}

	@GetMapping(C + "/{id}/edit")
	public String editForm(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		Screen s = screen(c);
		return FormPage.render(model, form(s), "Edit " + s.singular(), "/" + c + "/" + id + "/edit", "/" + c + "/" + id,
				ui.map(payments.get(s.kind(), id, s.shape())), false, null, response);
	}

	@PostMapping(C + "/{id}/edit")
	public String update(@PathVariable String c, @PathVariable int id, @RequestParam Map<String, String> submitted,
			Model model, HttpServletResponse response) {
		Screen s = screen(c);
		payments.get(s.kind(), id, s.shape());
		Map<String, Object> values = FormPage.values(form(s), submitted);
		var result = ui.attempt(() -> payments.update(s.kind(), id, ui.bind(values, s.request()).payment()));
		if (result.ok()) {
			return "redirect:/" + c + "/" + id;
		}
		return FormPage.render(model, form(s), "Edit " + s.singular(), "/" + c + "/" + id + "/edit", "/" + c + "/" + id,
				values, false, result.problem(), response);
	}

	/** P3, P4. */
	@GetMapping(C + "/{id}")
	public String show(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		return detail(model, response, screen(c), id, null);
	}

	private String detail(Model model, HttpServletResponse response, Screen s, int id, Ui.Problem problem) {
		Map<String, Object> payment = ui.map(payments.get(s.kind(), id, s.shape()));
		model.addAttribute("screen", s);
		model.addAttribute("id", id);
		model.addAttribute("payment", payment);
		model.addAttribute("partyName", choices.labels(s.partyChoices()).get(String.valueOf(payment.get(s.partyField()))));
		model.addAttribute("accountName", choices.labels("accounts").get(String.valueOf(payment.get(s.accountField()))));
		model.addAttribute("applications", settlement.applications(s.kind().settler(), id));
		model.addAttribute("error", problem == null ? null : problem.message());
		model.addAttribute("notice", null);
		if (problem != null) {
			response.setStatus(problem.status().value());
		}
		return "payment-detail";
	}

	private String act(Model model, HttpServletResponse response, Screen s, int id, Runnable action, String after) {
		payments.get(s.kind(), id, s.shape());
		var result = ui.attempt(action);
		return result.ok() ? "redirect:" + (after != null ? after : "/" + s.path() + "/" + id)
				: detail(model, response, s, id, result.problem());
	}

	@PostMapping(C + "/{id}/post")
	public String post(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		Screen s = screen(c);
		return act(model, response, s, id, () -> payments.post(s.kind(), id), null);
	}

	/** Administrators only (SecurityConfig). */
	@PostMapping(C + "/{id}/unpost")
	public String unpost(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		Screen s = screen(c);
		return act(model, response, s, id, () -> payments.unpost(s.kind(), id), null);
	}

	@PostMapping(C + "/{id}/apply")
	public String apply(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		Screen s = screen(c);
		return act(model, response, s, id, () -> settlement.apply(s.kind().settler(), id), null);
	}

	@PostMapping(C + "/{id}/delete")
	public String delete(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		Screen s = screen(c);
		return act(model, response, s, id, () -> payments.delete(s.kind(), id), "/" + s.path());
	}
}
