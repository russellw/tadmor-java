package com.belunaro.tadmor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsvTest {

	@Test
	void plainRecordsWithCrlfAndBlankLines() throws Exception {
		assertThat(Csv.parse("a,b,c\r\n\r\n1, 2 ,3\n\n")).containsExactly(List.of("a", "b", "c"), List.of("1", "2 ", "3"));
	}

	@Test
	void quotedFieldsHoldCommasQuotesAndLineBreaks() throws Exception {
		assertThat(Csv.parse("2026-01-01,\"Rent, \"\"office\"\"\",-100\n2026-01-02, \"two\nlines\",5"))
				.containsExactly(List.of("2026-01-01", "Rent, \"office\"", "-100"), List.of("2026-01-02", "two\nlines", "5"));
	}

	@Test
	void trailingEmptyFieldsAndNoFinalNewline() throws Exception {
		assertThat(Csv.parse("a,b,")).containsExactly(List.of("a", "b", ""));
		assertThat(Csv.parse("a,b,\n")).containsExactly(List.of("a", "b", ""));
		assertThat(Csv.parse("")).isEmpty();
	}

	@Test
	void malformedQuotingIsRefused() {
		assertThatThrownBy(() -> Csv.parse("a,b\"c,d")).isInstanceOf(Csv.MalformedCsvException.class);
		assertThatThrownBy(() -> Csv.parse("a,\"b\"c,d")).isInstanceOf(Csv.MalformedCsvException.class);
		assertThatThrownBy(() -> Csv.parse("a,\"unterminated")).isInstanceOf(Csv.MalformedCsvException.class);
	}
}
