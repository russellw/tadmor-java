package com.belunaro.tadmor.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import com.belunaro.tadmor.ui.Resource.Field;

import org.springframework.ui.Model;

/** Renders the generic form template (form.html) for screens with forms of their own. */
final class FormPage {

	private FormPage() {
	}

	static String render(Model model, Resource form, String title, String action, String cancel, Map<String, Object> values,
			boolean creating, Ui.Problem problem, HttpServletResponse response) {
		model.addAttribute("resource", form);
		model.addAttribute("key", null);
		model.addAttribute("creating", creating);
		model.addAttribute("values", values);
		model.addAttribute("error", problem == null ? null : problem.message());
		model.addAttribute("title", title);
		model.addAttribute("action", action);
		model.addAttribute("cancel", cancel);
		model.addAttribute("editable", true);
		if (problem != null) {
			response.setStatus(problem.status().value());
		}
		return "form";
	}

	/** The submitted values of the form's fields: checkboxes as booleans, blanks as null. */
	static Map<String, Object> values(Resource form, Map<String, String> submitted) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (Field f : form.fields()) {
			values.put(f.name(), f.kind().equals("checkbox") ? "true".equals(submitted.get(f.name())) : Ui.value(submitted, f.name()));
		}
		return values;
	}
}
