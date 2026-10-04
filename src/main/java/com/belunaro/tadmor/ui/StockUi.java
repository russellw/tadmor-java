package com.belunaro.tadmor.ui;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.service.StockService;
import com.belunaro.tadmor.service.StockService.MovementInput;
import com.belunaro.tadmor.ui.Resource.Field;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Stock movements (spec/domain.md §13.7, S1 to S3). */
@Controller
public class StockUi {

	/** S2: the quantity is a magnitude, signed by the type; an adjustment keeps the sign typed. */
	private static final Resource FORM = Resource.form("stock movement", List.of(
			Field.select("product_id", "Product", "stocked-products").requiredField(),
			Field.select("warehouse_id", "Warehouse", "warehouses").requiredField(),
			Field.select("movement_type", "Type", "movement-types").requiredField(),
			Field.of("movement_date", "Date (blank: today)", "date"),
			Field.required("quantity", "Quantity (an adjustment may be negative)", "decimal"),
			Field.of("unit_cost", "Unit cost", "decimal"),
			Field.of("reference", "Reference", "text"),
			Field.of("notes", "Notes", "textarea")));

	private final Ui ui;
	private final StockService stock;
	private final Choices choices;
	private final JdbcClient jdbc;

	public StockUi(Ui ui, StockService stock, Choices choices, JdbcClient jdbc) {
		this.ui = ui;
		this.stock = stock;
		this.choices = choices;
		this.jdbc = jdbc;
	}

	/** S1: newest first. */
	@GetMapping("/stock-movements")
	public String list(Model model) {
		model.addAttribute("rows", ui.maps(stock.list()));
		model.addAttribute("products", choices.labels("products"));
		model.addAttribute("warehouses", choices.labels("warehouses"));
		return "stock-list";
	}

	@GetMapping("/stock-movements/new")
	public String newForm(Model model, HttpServletResponse response) {
		Map<String, Object> values = new HashMap<>();
		values.put("movement_date", LocalDate.now(ZoneOffset.UTC).toString());
		return FormPage.render(model, FORM, "New stock movement", "/stock-movements/new", "/stock-movements", values, true,
				null, response);
	}

	@PostMapping("/stock-movements/new")
	public String create(@RequestParam Map<String, String> submitted, Model model, HttpServletResponse response) {
		Map<String, Object> values = FormPage.values(FORM, submitted);
		var result = ui.attempt(() -> stock.create(ui.bind(signed(values), MovementInput.class)));
		if (result.ok()) {
			return "redirect:/stock-movements/" + result.value();
		}
		return FormPage.render(model, FORM, "New stock movement", "/stock-movements/new", "/stock-movements", values, true,
				result.problem(), response);
	}

	@GetMapping("/stock-movements/{id}/edit")
	public String editForm(@PathVariable int id, Model model, HttpServletResponse response) {
		Map<String, Object> values = new HashMap<>(ui.map(stock.get(id)));
		if (!"adjustment".equals(values.get("movement_type"))) {
			values.put("quantity", String.valueOf(values.get("quantity")).replaceFirst("^-", ""));
		}
		return FormPage.render(model, FORM, "Edit stock movement", "/stock-movements/" + id + "/edit",
				"/stock-movements/" + id, values, false, null, response);
	}

	@PostMapping("/stock-movements/{id}/edit")
	public String update(@PathVariable int id, @RequestParam Map<String, String> submitted, Model model,
			HttpServletResponse response) {
		stock.get(id);
		Map<String, Object> values = FormPage.values(FORM, submitted);
		var result = ui.attempt(() -> stock.update(id, ui.bind(signed(values), MovementInput.class)));
		if (result.ok()) {
			return "redirect:/stock-movements/" + id;
		}
		return FormPage.render(model, FORM, "Edit stock movement", "/stock-movements/" + id + "/edit",
				"/stock-movements/" + id, values, false, result.problem(), response);
	}

	/** The values with the quantity signed by the movement type. */
	private static Map<String, Object> signed(Map<String, Object> values) {
		Map<String, Object> out = new HashMap<>(values);
		Object q = values.get("quantity");
		Object type = values.get("movement_type");
		if (q != null && type != null && !type.equals("adjustment")) {
			String magnitude = q.toString().replaceFirst("^[-+]", "");
			out.put("quantity", type.equals("issue") || type.equals("transfer_out") ? "-" + magnitude : magnitude);
		}
		return out;
	}

	/** S3. */
	@GetMapping("/stock-movements/{id}")
	public String show(@PathVariable int id, Model model, HttpServletResponse response) {
		return detail(model, response, id, null);
	}

	private String detail(Model model, HttpServletResponse response, int id, Ui.Problem problem) {
		Map<String, Object> m = ui.map(stock.get(id));
		model.addAttribute("id", id);
		model.addAttribute("m", m);
		model.addAttribute("productName", choices.labels("products").get(String.valueOf(m.get("product_id"))));
		model.addAttribute("warehouseName", choices.labels("warehouses").get(String.valueOf(m.get("warehouse_id"))));
		// Receipts typically credit Goods Received Not Invoiced (2150), so it is proposed.
		model.addAttribute("grni", jdbc.sql("SELECT id::text FROM accounts WHERE code = '2150'").query(String.class)
				.optional().orElse(null));
		model.addAttribute("error", problem == null ? null : problem.message());
		model.addAttribute("notice", null);
		if (problem != null) {
			response.setStatus(problem.status().value());
		}
		return "stock-detail";
	}

	private String act(Model model, HttpServletResponse response, int id, Runnable action, String after) {
		stock.get(id);
		var result = ui.attempt(action);
		return result.ok() ? "redirect:" + (after != null ? after : "/stock-movements/" + id)
				: detail(model, response, id, result.problem());
	}

	@PostMapping("/stock-movements/{id}/post")
	public String post(@PathVariable int id, @RequestParam(name = "credit_account_id", required = false) Integer credit,
			Model model, HttpServletResponse response) {
		return act(model, response, id, () -> stock.post(id, credit), null);
	}

	/** Administrators only (SecurityConfig). */
	@PostMapping("/stock-movements/{id}/unpost")
	public String unpost(@PathVariable int id, Model model, HttpServletResponse response) {
		return act(model, response, id, () -> stock.unpost(id), null);
	}

	@PostMapping("/stock-movements/{id}/delete")
	public String delete(@PathVariable int id, Model model, HttpServletResponse response) {
		return act(model, response, id, () -> stock.delete(id), "/stock-movements");
	}
}
