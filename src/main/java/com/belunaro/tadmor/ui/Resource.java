package com.belunaro.tadmor.ui;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A simple record type the UI lists and edits generically (ResourceUi): its
 * list columns, its form fields, and the service calls behind them. Field
 * and column names are the API's, so values convert straight to and from the
 * API's request and read records (Ui).
 *
 * @param path the URL path, also the API collection's name
 * @param key a row's key in URLs (an id or a code)
 * @param create returns the new record's key
 * @param delete null where the record type has no delete (master data never does)
 */
public record Resource(String path, String title, String singular, List<Column> columns, List<Field> fields,
		Supplier<List<?>> list, Function<String, Object> get, Function<Map<String, Object>, String> create,
		BiConsumer<String, Map<String, Object>> update, Function<Map<String, Object>, String> key, Consumer<String> delete,
		RowLink extra) {

	/**
	 * A list column. Kinds: text, amount, qty, bool, ref (the value's label
	 * from the named choices), and status.
	 */
	public record Column(String key, String label, String kind, String choices) {
		public static Column text(String key, String label) {
			return new Column(key, label, "text", null);
		}

		public static Column of(String key, String label, String kind) {
			return new Column(key, label, kind, null);
		}

		public static Column ref(String key, String label, String choices) {
			return new Column(key, label, "ref", choices);
		}
	}

	/**
	 * A form field. Kinds: text, email, password, decimal, integer, date,
	 * select (over the named choices), checkbox, and textarea. Modes: both,
	 * create (shown only on create), edit (only on edit), and fixed (chosen on
	 * create, read-only on edit).
	 */
	public record Field(String name, String label, String kind, String choices, boolean required, String mode) {
		public static Field of(String name, String label, String kind) {
			return new Field(name, label, kind, null, false, "both");
		}

		public static Field required(String name, String label, String kind) {
			return new Field(name, label, kind, null, true, "both");
		}

		public static Field select(String name, String label, String choices) {
			return new Field(name, label, "select", choices, false, "both");
		}

		public Field requiredField() {
			return new Field(name, label, kind, choices, true, mode);
		}

		public Field mode(String mode) {
			return new Field(name, label, kind, choices, required, mode);
		}

		public boolean shownOn(boolean creating) {
			return mode.equals("both") || mode.equals("fixed") || mode.equals(creating ? "create" : "edit");
		}

		public boolean readOnlyOn(boolean creating) {
			return !creating && mode.equals("fixed");
		}
	}

	/** An extra per-row action in the list, such as a user's password reset. */
	public record RowLink(String label, String suffix) {
	}

	public boolean deletable() {
		return delete != null;
	}

	/** Just a form's fields, for screens that use the generic form template without the generic controller. */
	public static Resource form(String singular, List<Field> fields) {
		return new Resource(null, null, singular, List.of(), fields, null, null, null, null, null, null, null);
	}
}
