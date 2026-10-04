package com.belunaro.tadmor.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.belunaro.tadmor.service.DatabaseErrors;
import com.belunaro.tadmor.service.ServiceException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What the UI controllers share. Records convert to maps keyed by the API's
 * snake_case names, and submitted forms convert back into the API's request
 * records, through the same Jackson configuration as the JSON API: the UI
 * speaks the spec's vocabulary and goes through the same services, so it
 * enforces exactly the API's rules (spec/domain.md §13 G5).
 */
@Component
public class Ui {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JsonMapper json;

	public Ui(JsonMapper json) {
		this.json = json;
	}

	/** A record as a map of its JSON fields. */
	public Map<String, Object> map(Object record) {
		return json.convertValue(record, MAP);
	}

	public List<Map<String, Object>> maps(List<?> records) {
		return records.stream().map(this::map).toList();
	}

	/** Form values (snake_case names) as a request record. */
	public <T> T bind(Map<String, ?> values, Class<T> type) {
		return json.convertValue(values, type);
	}

	public String toJson(Object value) {
		return json.writeValueAsString(value);
	}

	/** A form value, null when blank. */
	public static String value(Map<String, String> form, String name) {
		String v = form.get(name);
		return v == null || v.isBlank() ? null : v.strip();
	}

	/** Copies the named form values, blanks as null. */
	public static Map<String, Object> values(Map<String, String> form, String... names) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String n : names) {
			out.put(n, value(form, n));
		}
		return out;
	}

	/** A refusal to show the user: its status and message. */
	public record Problem(HttpStatus status, String message) {
	}

	/**
	 * Runs a service call, turning a refusal (a ServiceException, or a database
	 * rule) into a Problem to show next to the action instead of an error page.
	 */
	public <T> Result<T> attempt(Supplier<T> action) {
		try {
			return new Result<>(action.get(), null);
		} catch (ServiceException e) {
			return new Result<>(null, new Problem(e.status(), e.getMessage()));
		} catch (DataAccessException e) {
			Optional<ServiceException> refusal = DatabaseErrors.refusal(e);
			if (refusal.isEmpty()) {
				throw e;
			}
			return new Result<>(null, new Problem(refusal.get().status(), refusal.get().getMessage()));
		}
	}

	public Result<Void> attempt(Runnable action) {
		return attempt(() -> {
			action.run();
			return null;
		});
	}

	public record Result<T>(T value, Problem problem) {
		public boolean ok() {
			return problem == null;
		}
	}
}
