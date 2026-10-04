package com.belunaro.tadmor.ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.service.CatalogService;
import com.belunaro.tadmor.service.DocumentService;
import com.belunaro.tadmor.service.MailService;
import com.belunaro.tadmor.service.OrderService;
import com.belunaro.tadmor.service.OrderService.DocumentFromOrder;
import com.belunaro.tadmor.service.OrderService.LineQuantity;
import com.belunaro.tadmor.service.OrderService.StockFromOrder;
import com.belunaro.tadmor.service.PartyService;
import com.belunaro.tadmor.service.PostingService;
import com.belunaro.tadmor.service.PrintService;
import com.belunaro.tadmor.service.ServiceException;
import com.belunaro.tadmor.service.SettingsService;
import com.belunaro.tadmor.service.SettlementService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Invoices, bills, credit notes (spec/domain.md §13.4, D1 to D7) and orders
 * (§13.6, O1 to O7): lists, the line-item form, detail screens with the
 * actions their state allows, PDF and email, and order fulfilment. Actions
 * redirect back to the detail screen on success; a refusal re-shows it with
 * the server's message (G5).
 */
@Controller
public class DocumentUi {

	private static final String C = "/{c:sales-invoices|purchase-bills|sales-credit-notes|purchase-credit-notes|sales-orders|purchase-orders}";

	private static final String[] LINE_FIELDS = { "product_id", "description", "quantity", "price", "account_id", "tax_code",
			"tax_rate" };

	private final Ui ui;
	private final DocumentService documents;
	private final OrderService orders;
	private final PostingService posting;
	private final SettlementService settlement;
	private final PrintService printing;
	private final MailService mail;
	private final CatalogService catalog;
	private final PartyService parties;
	private final SettingsService settings;
	private final Choices choices;

	public DocumentUi(Ui ui, DocumentService documents, OrderService orders, PostingService posting,
			SettlementService settlement, PrintService printing, MailService mail, CatalogService catalog,
			PartyService parties, SettingsService settings, Choices choices) {
		this.ui = ui;
		this.documents = documents;
		this.orders = orders;
		this.posting = posting;
		this.settlement = settlement;
		this.printing = printing;
		this.mail = mail;
		this.catalog = catalog;
		this.parties = parties;
		this.settings = settings;
		this.choices = choices;
	}

	private static DocScreen screen(String c) {
		return DocScreen.of(c).orElseThrow(ServiceException::notFound);
	}

	// ---- reads ----

	private Map<String, Object> read(DocScreen s, int id) {
		return ui.map(s.isOrder() ? orders.get(s.order, id, s.shape) : documents.get(s.kind, id, s.shape));
	}

	private List<Map<String, Object>> lines(DocScreen s, int id) {
		return ui.maps(s.isOrder() ? orders.lines(s.order, id, s.lineShape) : documents.lines(s.kind, id, s.lineShape));
	}

	/** D1, O1. */
	@GetMapping(C)
	public String list(@PathVariable String c, Model model) {
		DocScreen s = screen(c);
		model.addAttribute("screen", s);
		model.addAttribute("rows", ui.maps(s.isOrder() ? orders.list(s.order, s.shape) : documents.list(s.kind, s.shape)));
		model.addAttribute("parties", choices.labels(s.getPartyChoices()));
		return "doc-list";
	}

	// ---- the form (D2, O2) ----

	@GetMapping(C + "/new")
	public String newForm(@PathVariable String c, Model model) {
		DocScreen s = screen(c);
		Map<String, Object> header = new HashMap<>();
		header.put(s.dateField, LocalDate.now(ZoneOffset.UTC).toString());
		header.put("currency_code", settings.settings().baseCurrency());
		return form(model, s, null, header, List.of(emptyLine()), null);
	}

	@PostMapping(C + "/new")
	public String create(@PathVariable String c, @RequestParam MultiValueMap<String, String> form, Model model,
			HttpServletResponse response) {
		DocScreen s = screen(c);
		Map<String, Object> header = header(s, form);
		List<Map<String, Object>> lines = formLines(form);
		var result = ui.attempt(() -> documents.create(s.kind, request(s, header, lines)));
		if (result.ok()) {
			return "redirect:/" + c + "/" + result.value();
		}
		response.setStatus(result.problem().status().value());
		return form(model, s, null, header, lines.isEmpty() ? List.of(emptyLine()) : lines, result.problem().message());
	}

	@GetMapping(C + "/{id}/edit")
	public String editForm(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = screen(c);
		Map<String, Object> doc = read(s, id);
		List<Map<String, Object>> lines = lines(s, id).stream().map(l -> editableLine(s, l)).toList();
		if (!"draft".equals(doc.get("status"))) {
			return detail(model, response, s, id, new Ui.Problem(HttpStatus.CONFLICT, "Only a draft can be edited."), null);
		}
		return form(model, s, id, doc, lines.isEmpty() ? List.of(emptyLine()) : lines, null);
	}

	@PostMapping(C + "/{id}/edit")
	public String update(@PathVariable String c, @PathVariable int id, @RequestParam MultiValueMap<String, String> form,
			Model model, HttpServletResponse response) {
		DocScreen s = screen(c);
		read(s, id);
		Map<String, Object> header = header(s, form);
		List<Map<String, Object>> lines = formLines(form);
		var result = ui.attempt(() -> documents.update(s.kind, id, request(s, header, lines)));
		if (result.ok()) {
			return "redirect:/" + c + "/" + id;
		}
		response.setStatus(result.problem().status().value());
		return form(model, s, id, header, lines.isEmpty() ? List.of(emptyLine()) : lines, result.problem().message());
	}

	private Map<String, Object> header(DocScreen s, MultiValueMap<String, String> form) {
		Map<String, Object> header = new LinkedHashMap<>();
		for (String name : new String[] { s.numberField, s.getPartyField(), s.dateField, s.dueField, "currency_code",
				"reference", "memo" }) {
			if (name != null) {
				header.put(name, Ui.value(form.toSingleValueMap(), name));
			}
		}
		return header;
	}

	/** The submitted lines, by the editor's generic names; rows left entirely blank are dropped. */
	private static List<Map<String, Object>> formLines(MultiValueMap<String, String> form) {
		List<String> descriptions = form.getOrDefault("l_description", List.of());
		List<Map<String, Object>> lines = new ArrayList<>();
		for (int i = 0; i < descriptions.size(); i++) {
			Map<String, Object> line = new LinkedHashMap<>();
			for (String f : LINE_FIELDS) {
				List<String> values = form.getOrDefault("l_" + f, List.of());
				String v = i < values.size() ? values.get(i) : null;
				line.put(f, v == null || v.isBlank() ? null : v.strip());
			}
			boolean blank = line.get("product_id") == null && line.get("description") == null && line.get("price") == null;
			if (!blank) {
				lines.add(line);
			}
		}
		return lines;
	}

	/** A request record of the screen's kind, its lines renamed to the kind's fields. */
	private DocumentService.DocumentInput request(DocScreen s, Map<String, Object> header, List<Map<String, Object>> lines) {
		Map<String, Object> body = new LinkedHashMap<>(header);
		body.put("lines", lines.stream().map(l -> {
			Map<String, Object> line = new LinkedHashMap<>(l);
			line.put(s.getPriceField(), line.remove("price"));
			line.put(s.getAccountField(), line.remove("account_id"));
			return line;
		}).toList());
		return ui.bind(body, s.request).document();
	}

	private static Map<String, Object> editableLine(DocScreen s, Map<String, Object> read) {
		Map<String, Object> line = new HashMap<>(read);
		line.put("price", read.get(s.getPriceField()));
		line.put("account_id", read.get(s.getAccountField()));
		return line;
	}

	private static Map<String, Object> emptyLine() {
		Map<String, Object> line = new HashMap<>();
		line.put("quantity", "1");
		line.put("tax_rate", "0");
		return line;
	}

	private String form(Model model, DocScreen s, Integer id, Map<String, Object> header, List<Map<String, Object>> lines,
			String error) {
		model.addAttribute("screen", s);
		model.addAttribute("id", id);
		model.addAttribute("header", header);
		model.addAttribute("lines", lines);
		model.addAttribute("error", error);
		model.addAttribute("action", "/" + s.path + (id == null ? "/new" : "/" + id + "/edit"));
		model.addAttribute("cancel", "/" + s.path + (id == null ? "" : "/" + id));
		// Inside a <script> element: "</" must not end it early.
		model.addAttribute("clientData", ui.toJson(clientData(s)).replace("</", "<\\/"));
		return "doc-form";
	}

	/** What the line editor fills from: each product's description, tax code, and (sales side) price and account. */
	private Map<String, Object> clientData(DocScreen s) {
		Map<String, Object> products = new HashMap<>();
		for (CatalogService.Product p : catalog.products()) {
			Map<String, Object> fill = new HashMap<>();
			fill.put("description", p.name());
			fill.put("tax_code", p.taxCode());
			fill.put("price", s.sales ? p.unitPrice() : null);
			fill.put("account", s.sales && p.revenueAccountId() != null ? p.revenueAccountId().toString() : null);
			products.put(Integer.toString(p.id()), fill);
		}
		Map<String, String> taxes = new HashMap<>();
		catalog.taxCodes().forEach(t -> taxes.put(t.code(), t.rate()));
		Map<String, String> partyCurrency = new HashMap<>();
		if (s.sales) {
			parties.customers().stream().filter(p -> p.currencyCode() != null)
					.forEach(p -> partyCurrency.put(Integer.toString(p.id()), p.currencyCode()));
		} else {
			parties.suppliers().stream().filter(p -> p.currencyCode() != null)
					.forEach(p -> partyCurrency.put(Integer.toString(p.id()), p.currencyCode()));
		}
		return Map.of("products", products, "taxes", taxes, "partyCurrency", partyCurrency);
	}

	// ---- the detail screen and its actions (D3 to D7, O3 to O7) ----

	@GetMapping(C + "/{id}")
	public String show(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		return detail(model, response, screen(c), id, null, null);
	}

	private String detail(Model model, HttpServletResponse response, DocScreen s, int id, Ui.Problem problem, String notice) {
		Map<String, Object> doc = read(s, id);
		List<Map<String, Object>> lines = lines(s, id);
		model.addAttribute("screen", s);
		model.addAttribute("id", id);
		model.addAttribute("doc", doc);
		model.addAttribute("lines", lines);
		model.addAttribute("partyName", choices.labels(s.getPartyChoices()).get(String.valueOf(doc.get(s.getPartyField()))));
		model.addAttribute("subtotal", sum(lines, "line_subtotal"));
		model.addAttribute("taxTotal", sum(lines, "tax_amount"));
		model.addAttribute("orderLinked", !s.isOrder() && lines.stream().anyMatch(l -> l.get("order_line_id") != null));
		model.addAttribute("applications", s.isCreditNote() ? settlement.applications(s.settler, id) : List.of());
		model.addAttribute("error", problem == null ? null : problem.message());
		model.addAttribute("notice", notice);
		if (problem != null) {
			response.setStatus(problem.status().value());
		}
		return s.isOrder() ? "order-detail" : "doc-detail";
	}

	private static String sum(List<Map<String, Object>> lines, String field) {
		return lines.stream().map(l -> new BigDecimal(String.valueOf(l.get(field)))).reduce(BigDecimal.ZERO, BigDecimal::add)
				.toPlainString();
	}

	/** Runs an action, then shows the detail screen: after a redirect on success, directly with the refusal otherwise. */
	private String act(Model model, HttpServletResponse response, DocScreen s, int id, Runnable action, String after) {
		read(s, id); // 404 for an unknown document
		var result = ui.attempt(action);
		if (result.ok()) {
			return "redirect:" + (after != null ? after : "/" + s.path + "/" + id);
		}
		return detail(model, response, s, id, result.problem(), null);
	}

	@PostMapping(C + "/{id}/post")
	public String post(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = screen(c);
		return act(model, response, s, id, () -> posting.post(s.kind, id), null);
	}

	/** Administrators only (SecurityConfig). */
	@PostMapping(C + "/{id}/unpost")
	public String unpost(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = screen(c);
		return act(model, response, s, id, () -> posting.unpost(s.kind, id), null);
	}

	@PostMapping(C + "/{id}/delete")
	public String delete(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = screen(c);
		return act(model, response, s, id, () -> documents.delete(s.kind, id), "/" + s.path);
	}

	/** Credit notes: apply what is left to the party's open documents. */
	@PostMapping(C + "/{id}/apply")
	public String apply(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = screen(c);
		if (!s.isCreditNote()) {
			throw ServiceException.notFound();
		}
		return act(model, response, s, id, () -> settlement.apply(s.settler, id), null);
	}

	@PostMapping(C + "/{id}/confirm")
	public String confirm(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = orderScreen(c);
		return act(model, response, s, id, () -> orders.confirm(s.order, id), null);
	}

	@PostMapping(C + "/{id}/close")
	public String close(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = orderScreen(c);
		return act(model, response, s, id, () -> orders.close(s.order, id), null);
	}

	@PostMapping(C + "/{id}/cancel")
	public String cancel(@PathVariable String c, @PathVariable int id, Model model, HttpServletResponse response) {
		DocScreen s = orderScreen(c);
		return act(model, response, s, id, () -> orders.cancel(s.order, id), null);
	}

	private static DocScreen orderScreen(String c) {
		DocScreen s = screen(c);
		if (!s.isOrder()) {
			throw ServiceException.notFound();
		}
		return s;
	}

	/** D7, O7: blank recipients mean the counterparty's email on file; shows the address used, or the refusal. */
	@PostMapping(C + "/{id}/email")
	public String email(@PathVariable String c, @PathVariable int id, @RequestParam(defaultValue = "") String to, Model model,
			HttpServletResponse response) {
		DocScreen s = screen(c);
		read(s, id);
		List<String> given = Arrays.stream(to.split("[,;\\s]+")).filter(t -> !t.isBlank()).toList();
		var result = ui.attempt(() -> {
			List<String> recipients = given.isEmpty() ? List.of(printing.recipient(s.printable, id).orElseThrow(
					() -> ServiceException.unprocessable("This counterparty has no email address on file; enter a recipient.")))
					: given;
			mail.send(recipients, printing.print(s.printable, id));
			return recipients;
		});
		if (result.ok()) {
			return detail(model, response, s, id, null, "Sent to " + String.join(", ", result.value()) + ".");
		}
		return detail(model, response, s, id, result.problem(), null);
	}

	// ---- order fulfilment (O5, O6) ----

	@GetMapping(C + "/{id}/{verb:invoice|bill|ship|receive}")
	public String fulfilForm(@PathVariable String c, @PathVariable int id, @PathVariable String verb, Model model,
			HttpServletResponse response) {
		DocScreen s = fulfilScreen(c, verb);
		return fulfil(model, s, id, verb, Map.of(s.dateFieldFor(verb), LocalDate.now(ZoneOffset.UTC).toString()), null,
				response, null);
	}

	@PostMapping(C + "/{id}/{verb:invoice|bill|ship|receive}")
	public String fulfilSubmit(@PathVariable String c, @PathVariable int id, @PathVariable String verb,
			@RequestParam MultiValueMap<String, String> form, Model model, HttpServletResponse response) {
		DocScreen s = fulfilScreen(c, verb);
		Map<String, String> values = form.toSingleValueMap();
		List<String> lineIds = form.getOrDefault("f_line_id", List.of());
		List<String> quantities = form.getOrDefault("f_quantity", List.of());
		Map<String, String> requested = new HashMap<>();
		List<LineQuantity> lines = new ArrayList<>();
		for (int i = 0; i < lineIds.size() && i < quantities.size(); i++) {
			String q = quantities.get(i).strip();
			requested.put(lineIds.get(i), q);
			if (!q.isEmpty() && !isZero(q)) {
				lines.add(new LineQuantity(Integer.valueOf(lineIds.get(i)), q));
			}
		}
		boolean document = verb.equals("invoice") || verb.equals("bill");
		if (lines.isEmpty()) {
			// An empty request would mean "everything remaining": refuse rather than take it all.
			return fulfil(model, s, id, verb, values, requested, response,
					new Ui.Problem(HttpStatus.UNPROCESSABLE_ENTITY, "Every quantity is zero: there is nothing to " + verb + "."));
		}
		if (document) {
			var result = ui.attempt(() -> orders.document(s.order, id, new DocumentFromOrder(values.get(numberFieldFor(verb)),
					values.get(s.dateFieldFor(verb)), values.get("due_date"), lines)));
			if (result.ok()) {
				return "redirect:/" + (s.sales ? "sales-invoices/" : "purchase-bills/") + result.value();
			}
			return fulfil(model, s, id, verb, values, requested, response, result.problem());
		}
		String warehouse = Ui.value(values, "warehouse_id");
		var result = ui.attempt(() -> orders.stock(s.order, id, new StockFromOrder(
				warehouse == null ? null : Integer.valueOf(warehouse), values.get("movement_date"),
				Ui.value(values, "reference"), lines)));
		if (result.ok()) {
			model.addAttribute("movements", result.value());
			return detail(model, response, s, id, null, (s.sales ? "Shipped" : "Received") + ": draft stock movements created.");
		}
		return fulfil(model, s, id, verb, values, requested, response, result.problem());
	}

	private static DocScreen fulfilScreen(String c, String verb) {
		DocScreen s = orderScreen(c);
		if (!verb.equals(s.getDocumentVerb()) && !verb.equals(s.getStockVerb())) {
			throw ServiceException.notFound();
		}
		return s;
	}

	private static String numberFieldFor(String verb) {
		return verb.equals("invoice") ? "invoice_number" : "bill_number";
	}

	private static boolean isZero(String q) {
		try {
			return new BigDecimal(q).signum() == 0;
		} catch (NumberFormatException e) {
			return false; // let the server refuse it
		}
	}

	private String fulfil(Model model, DocScreen s, int id, String verb, Map<String, String> values,
			Map<String, String> requested, HttpServletResponse response, Ui.Problem problem) {
		Map<String, Object> order = read(s, id);
		boolean document = verb.equals("invoice") || verb.equals("bill");
		String remaining = document ? (s.sales ? "qty_to_invoice" : "qty_to_bill") : (s.sales ? "qty_to_ship" : "qty_to_receive");
		List<Map<String, Object>> outstanding = lines(s, id).stream()
				.filter(l -> new BigDecimal(String.valueOf(l.get(remaining))).signum() > 0).toList();
		model.addAttribute("screen", s);
		model.addAttribute("id", id);
		model.addAttribute("order", order);
		model.addAttribute("verb", verb);
		model.addAttribute("document", document);
		model.addAttribute("numberField", document ? numberFieldFor(verb) : null);
		model.addAttribute("dateField", s.dateFieldFor(verb));
		model.addAttribute("remaining", remaining);
		model.addAttribute("outstanding", outstanding);
		model.addAttribute("values", values);
		model.addAttribute("requested", requested == null ? Map.of() : requested);
		model.addAttribute("error", problem == null ? null : problem.message());
		if (problem != null) {
			response.setStatus(problem.status().value());
		} else if (Objects.equals(order.get("status"), "open") && outstanding.isEmpty()) {
			model.addAttribute("error", "Nothing is left to " + verb + " on this order.");
		}
		return "order-fulfil";
	}
}
