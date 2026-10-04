package com.belunaro.tadmor.service;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal RFC 4180 reader, enough for bank statement imports and kept to
 * the behavior of tadmor's (Go's encoding/csv, with leading space trimmed):
 * comma-separated fields, double-quoted fields that may hold commas, quotes
 * written twice, and line breaks, CRLF or LF records, and empty lines
 * skipped. A bare quote in an unquoted field, text after a closing quote,
 * or an unterminated quote is malformed. The JDK has no CSV reader, and
 * this is too small a need to take on a library for.
 */
final class Csv {

	private Csv() {
	}

	/** Thrown for malformed CSV. */
	static final class MalformedCsvException extends Exception {
		MalformedCsvException(String message) {
			super(message);
		}
	}

	static List<List<String>> parse(String text) throws MalformedCsvException {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		int line = 1;
		int i = 0;
		int n = text.length();
		boolean atRecordStart = true;
		while (i < n) {
			// An empty line is no record at all.
			if (atRecordStart && (text.charAt(i) == '\n' || text.startsWith("\r\n", i))) {
				i += text.charAt(i) == '\n' ? 1 : 2;
				line++;
				continue;
			}
			atRecordStart = false;
			while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
				i++;
			}
			if (i < n && text.charAt(i) == '"') {
				i++;
				while (true) {
					if (i >= n) {
						throw new MalformedCsvException("line " + line + ": unterminated quoted field");
					}
					char c = text.charAt(i);
					if (c == '"') {
						if (i + 1 < n && text.charAt(i + 1) == '"') {
							field.append('"');
							i += 2;
							continue;
						}
						i++;
						break;
					}
					if (c == '\n') {
						line++;
					}
					field.append(c);
					i++;
				}
				if (i < n && text.charAt(i) != ',' && text.charAt(i) != '\n' && !text.startsWith("\r\n", i)) {
					throw new MalformedCsvException("line " + line + ": text after a closing quote");
				}
			} else {
				while (i < n && text.charAt(i) != ',' && text.charAt(i) != '\n' && !text.startsWith("\r\n", i)) {
					if (text.charAt(i) == '"') {
						throw new MalformedCsvException("line " + line + ": a quote in an unquoted field");
					}
					field.append(text.charAt(i));
					i++;
				}
			}
			record.add(field.toString());
			field.setLength(0);
			if (i < n && text.charAt(i) == ',') {
				i++;
				if (i == n) {
					record.add("");
				}
				continue;
			}
			// End of record.
			records.add(record);
			record = new ArrayList<>();
			atRecordStart = true;
			if (i < n) {
				i += text.charAt(i) == '\n' ? 1 : 2;
				line++;
			}
		}
		if (!record.isEmpty()) {
			records.add(record);
		}
		return records;
	}
}
