package com.belunaro.tadmor.ui;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/**
 * Display formatting for templates, as {@code ${@fmt.amount(x)}}. Amounts
 * stay exact decimals (spec/domain.md §13 G7): grouped, with trailing zeros
 * dropped down to two places, never rounded.
 */
@Component("fmt")
public class Format {

	/** "1,234.50", "10.0011", "" for null. */
	public String amount(Object value) {
		if (value == null || value.toString().isBlank()) {
			return "";
		}
		String s = value.toString();
		String sign = "";
		if (s.startsWith("-")) {
			sign = "-";
			s = s.substring(1);
		}
		int dot = s.indexOf('.');
		String integer = dot < 0 ? s : s.substring(0, dot);
		StringBuilder fraction = new StringBuilder(dot < 0 ? "" : s.substring(dot + 1).replaceAll("0+$", ""));
		while (fraction.length() < 2) {
			fraction.append('0');
		}
		StringBuilder grouped = new StringBuilder();
		for (int i = 0; i < integer.length(); i++) {
			if (i > 0 && (integer.length() - i) % 3 == 0) {
				grouped.append(',');
			}
			grouped.append(integer.charAt(i));
		}
		return sign + grouped + "." + fraction;
	}

	/** A quantity or rate without trailing zeros: "2", "8.25". */
	public String qty(Object value) {
		if (value == null) {
			return "";
		}
		String s = value.toString();
		return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
	}

	public String bool(Object value) {
		return Boolean.TRUE.equals(value) ? "Yes" : "No";
	}

	/** Whether a decimal is negative, for styling. */
	public boolean negative(Object value) {
		try {
			return value != null && new BigDecimal(value.toString()).signum() < 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** Whether a decimal is non-zero. */
	public boolean nonZero(Object value) {
		try {
			return value != null && new BigDecimal(value.toString()).signum() != 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** The sum of decimal strings, exactly. */
	public String sum(Iterable<?> values) {
		BigDecimal total = BigDecimal.ZERO;
		for (Object v : values) {
			if (v != null && !v.toString().isBlank()) {
				total = total.add(new BigDecimal(v.toString()));
			}
		}
		return total.toPlainString();
	}

	/** A status as words: "not_yet_due" becomes "not yet due". */
	public String words(Object value) {
		return value == null ? "" : value.toString().replace('_', ' ');
	}
}
