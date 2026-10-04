package com.belunaro.tadmor.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.SimplePropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Named SQL parameters from a request record's components, plus extra values
 * such as the id from the path: {@code :name} binds {@code in.name()}.
 */
final class Params implements SqlParameterSource {

	private final SqlParameterSource record;
	private final Map<String, Object> extra;

	private Params(Object record, Map<String, Object> extra) {
		this.record = new SimplePropertySqlParameterSource(record);
		this.extra = extra;
	}

	/** The record's components, plus extra values given as name, value pairs. */
	static Params of(Object record, Object... extra) {
		Map<String, Object> values = new HashMap<>();
		for (int i = 0; i < extra.length; i += 2) {
			values.put((String) extra[i], extra[i + 1]);
		}
		return new Params(record, values);
	}

	@Override
	public boolean hasValue(String name) {
		return extra.containsKey(name) || record.hasValue(name);
	}

	@Override
	public Object getValue(String name) {
		return extra.containsKey(name) ? extra.get(name) : record.getValue(name);
	}

	@Override
	public int getSqlType(String name) {
		return extra.containsKey(name) ? TYPE_UNKNOWN : record.getSqlType(name);
	}
}
