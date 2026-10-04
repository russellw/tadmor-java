package com.belunaro.tadmor.ui;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.security.CurrentUser;
import com.belunaro.tadmor.service.ServiceException;
import com.belunaro.tadmor.service.SettingsService;
import com.belunaro.tadmor.service.UserService;
import com.belunaro.tadmor.ui.Resource.Column;
import com.belunaro.tadmor.ui.Resource.Field;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Lists and forms for the simple record types (Resources): a list that opens
 * each record's form, a new-record form, and delete where it exists (only
 * exchange rates). A refused save re-shows the form with the server's
 * message next to it (spec/domain.md §13 G5). Also the users' password reset
 * (M7) and the ledger settings (M8), which are forms of their own.
 */
@Controller
public class ResourceUi {

	private static final String R = "/{r:organizations|customers|suppliers|products|accounts|tax-codes|payment-terms|warehouses|users|exchange-rates}";

	private final Resources resources;
	private final Ui ui;
	private final Choices choices;
	private final UserService users;
	private final SettingsService settings;

	public ResourceUi(Resources resources, Ui ui, Choices choices, UserService users, SettingsService settings) {
		this.resources = resources;
		this.ui = ui;
		this.choices = choices;
		this.users = users;
		this.settings = settings;
	}

	public record Row(String key, Map<String, Object> values) {
	}

	private Resource resource(String path) {
		return resources.get(path).orElseThrow(ServiceException::notFound);
	}

	@GetMapping(R)
	public String list(@PathVariable String r, Model model) {
		Resource res = resource(r);
		List<Row> rows = ui.maps(res.list().get()).stream().map(m -> new Row(res.key().apply(m), m)).toList();
		Map<String, Map<String, String>> labels = new HashMap<>();
		for (Column c : res.columns()) {
			if (c.choices() != null) {
				labels.put(c.key(), choices.labels(c.choices()));
			}
		}
		model.addAttribute("resource", res);
		model.addAttribute("rows", rows);
		model.addAttribute("labels", labels);
		return "list";
	}

	@GetMapping(R + "/new")
	public String newForm(@PathVariable String r, Model model) {
		return form(model, resource(r), null, new HashMap<>(), null);
	}

	@PostMapping(R + "/new")
	public String create(@PathVariable String r, @RequestParam Map<String, String> form, Model model,
			HttpServletResponse response) {
		Resource res = resource(r);
		Map<String, Object> values = values(res, form, true);
		var result = ui.attempt(() -> res.create().apply(values));
		if (result.ok()) {
			return "redirect:/" + r;
		}
		response.setStatus(result.problem().status().value());
		return form(model, res, null, values, result.problem().message());
	}

	@GetMapping(R + "/{key}")
	public String editForm(@PathVariable String r, @PathVariable String key, Model model) {
		Resource res = resource(r);
		return form(model, res, key, ui.map(res.get().apply(key)), null);
	}

	@PostMapping(R + "/{key}")
	public String update(@PathVariable String r, @PathVariable String key, @RequestParam Map<String, String> form, Model model,
			HttpServletResponse response) {
		Resource res = resource(r);
		res.get().apply(key); // 404 for an unknown record
		Map<String, Object> values = values(res, form, false);
		var result = ui.attempt(() -> res.update().accept(key, values));
		if (result.ok()) {
			return "redirect:/" + r;
		}
		response.setStatus(result.problem().status().value());
		return form(model, res, key, values, result.problem().message());
	}

	@PostMapping(R + "/{key}/delete")
	public String delete(@PathVariable String r, @PathVariable String key, Model model, HttpServletResponse response) {
		Resource res = resource(r);
		if (!res.deletable()) {
			throw ServiceException.notFound();
		}
		var result = ui.attempt(() -> res.delete().accept(key));
		if (result.ok()) {
			return "redirect:/" + r;
		}
		response.setStatus(result.problem().status().value());
		return form(model, res, key, ui.map(res.get().apply(key)), result.problem().message());
	}

	/** The submitted fields: checkboxes as booleans, blanks as null. */
	private static Map<String, Object> values(Resource res, Map<String, String> form, boolean creating) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (Field f : res.fields()) {
			if (f.kind().equals("checkbox")) {
				values.put(f.name(), "true".equals(form.get(f.name())));
			} else {
				values.put(f.name(), Ui.value(form, f.name()));
			}
		}
		return values;
	}

	private String form(Model model, Resource res, String key, Map<String, Object> values, String error) {
		model.addAttribute("resource", res);
		model.addAttribute("key", key);
		model.addAttribute("creating", key == null);
		model.addAttribute("values", values);
		model.addAttribute("error", error);
		model.addAttribute("title", (key == null ? "New " : "Edit ") + res.singular());
		model.addAttribute("action", "/" + res.path() + (key == null ? "/new" : "/" + key));
		model.addAttribute("cancel", "/" + res.path());
		model.addAttribute("editable", true);
		return "form";
	}

	// ---- users: password reset (M7) ----

	@GetMapping("/users/{id}/password")
	public String passwordForm(@PathVariable int id, Model model) {
		return passwordPage(model, id, null);
	}

	@PostMapping("/users/{id}/password")
	public String resetPassword(@PathVariable int id, @RequestParam(defaultValue = "") String password, Model model,
			HttpServletResponse response) {
		var result = ui.attempt(() -> users.setPassword(id, password));
		if (result.ok()) {
			return "redirect:/users";
		}
		response.setStatus(result.problem().status().value());
		return passwordPage(model, id, result.problem().message());
	}

	private String passwordPage(Model model, int id, String error) {
		model.addAttribute("user", users.get(id));
		model.addAttribute("error", error);
		return "user-password";
	}

	// ---- settings (M8): read-only for non-administrators ----

	private static final List<Field> SETTINGS = List.of(
			Field.select("base_currency", "Base currency", "currencies").requiredField(),
			Field.select("fx_gain_loss_account_id", "FX gain/loss account", "postable-accounts"));

	private static final Resource SETTINGS_FORM = new Resource("settings", "Settings", "settings", List.of(), SETTINGS,
			null, null, null, null, null, null, null);

	@GetMapping("/settings")
	public String settingsForm(@AuthenticationPrincipal CurrentUser user, Model model) {
		return settingsPage(model, user, ui.map(settings.settings()), null);
	}

	@PostMapping("/settings")
	public String saveSettings(@AuthenticationPrincipal CurrentUser user, @RequestParam Map<String, String> form,
			Model model, HttpServletResponse response) {
		Map<String, Object> values = Ui.values(form, "base_currency", "fx_gain_loss_account_id");
		var result = ui.attempt(() -> settings.updateSettings(ui.bind(values, SettingsService.Settings.class)));
		if (result.ok()) {
			return "redirect:/settings";
		}
		response.setStatus(result.problem().status().value());
		return settingsPage(model, user, values, result.problem().message());
	}

	private String settingsPage(Model model, CurrentUser user, Map<String, Object> values, String error) {
		model.addAttribute("resource", SETTINGS_FORM);
		model.addAttribute("key", "settings");
		model.addAttribute("creating", false);
		model.addAttribute("values", values);
		model.addAttribute("error", error);
		model.addAttribute("title", "Ledger settings");
		model.addAttribute("action", "/settings");
		model.addAttribute("cancel", null);
		model.addAttribute("editable", user.isAdmin());
		return "form";
	}
}
