package com.belunaro.tadmor.printing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import org.junit.jupiter.api.Test;

/**
 * The PDF's structure, checked byte by byte since no PDF reader is at hand:
 * every cross-reference offset lands on its object, startxref on the table,
 * and the page streams inflate to the expected text.
 */
class LayoutTest {

	private static Layout.Document document(int lineCount) {
		List<Layout.Line> lines = new ArrayList<>();
		for (int i = 1; i <= lineCount; i++) {
			lines.add(new Layout.Line(i, "Widget (large) \\ number " + i, "2.0000", "1234.5000", "8.2500", "2469.0000"));
		}
		Layout.Party customer = new Layout.Party("Acme Ltd", "Acme Limited", "IE123", List.of("1 Main St", "Dublin"));
		Layout.Party self = new Layout.Party("Our Co", null, null, List.of());
		return new Layout.Document("Invoice", "INV-7", "posted", "EUR",
				List.of(new Layout.Meta("Invoice no.", "INV-7"), new Layout.Meta("Currency", "EUR")), "BILL TO", customer,
				self, "UNIT PRICE", "2469.0000", "203.6925", "2672.6925", "100.0000", "2572.6925", "Amount paid",
				"Balance due", "PO-77", "Thanks for your business €", lines);
	}

	private static String latin1(byte[] b) {
		return new String(b, StandardCharsets.ISO_8859_1);
	}

	/** The inflated content of every page stream, in order. */
	private static List<String> streams(byte[] pdf) throws Exception {
		List<String> out = new ArrayList<>();
		String s = latin1(pdf);
		Matcher m = Pattern.compile("<< /Length (\\d+) /Filter /FlateDecode >>\nstream\n").matcher(s);
		while (m.find()) {
			int start = m.end();
			int length = Integer.parseInt(m.group(1));
			Inflater inflater = new Inflater();
			inflater.setInput(pdf, start, length);
			ByteArrayOutputStream text = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			while (!inflater.finished()) {
				text.write(chunk, 0, inflater.inflate(chunk));
			}
			out.add(latin1(text.toByteArray()));
			assertThat(s.substring(start + length)).startsWith("\nendstream\nendobj\n");
		}
		return out;
	}

	@Test
	void crossReferencesPointAtTheirObjects() {
		byte[] pdf = Layout.render(document(3));
		String s = latin1(pdf);
		assertThat(s).startsWith("%PDF-1.4\n").endsWith("%%EOF\n");

		Matcher startxref = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(s);
		assertThat(startxref.find()).isTrue();
		int xref = Integer.parseInt(startxref.group(1));
		assertThat(s.substring(xref)).startsWith("xref\n0 7\n0000000000 65535 f \n");

		Matcher entries = Pattern.compile("(\\d{10}) 00000 n \n").matcher(s.substring(xref));
		int object = 1;
		while (entries.find()) {
			assertThat(s.substring(Integer.parseInt(entries.group(1)))).startsWith(object + " 0 obj\n");
			object++;
		}
		assertThat(object - 1).as("catalog, pages, two fonts, one page and its content").isEqualTo(6);
	}

	@Test
	void pagesCarryTheDocument() throws Exception {
		String page = streams(Layout.render(document(3))).get(0);
		assertThat(page).contains("(INVOICE) Tj", "(INV-7) Tj", "(BILL TO) Tj", "(Acme Ltd) Tj", "(Tax ID: IE123) Tj",
				"(Our Co) Tj", "(UNIT PRICE) Tj", "(1,234.50) Tj", "(8.25) Tj", "(EUR 2,672.6925) Tj", "(203.6925) Tj", "(Amount paid) Tj",
				"(Reference: PO-77) Tj", "(Invoice INV-7  ·  Page 1 of 1) Tj");
		assertThat(page).as("parentheses and backslashes are escaped").contains("(Widget \\(large\\) \\\\ number 1) Tj");
		assertThat(page).as("the euro sign is WinAnsi 0x80").contains("business \u0080");
	}

	@Test
	void longDocumentsContinueOnFurtherPages() throws Exception {
		List<String> pages = streams(Layout.render(document(80)));
		assertThat(pages).hasSizeGreaterThan(1);
		assertThat(pages.get(0)).contains("Page 1 of " + pages.size());
		assertThat(pages.get(pages.size() - 1)).contains("(Total) Tj");
		assertThat(Collections.frequency(pages.stream().map(p -> p.contains("(DESCRIPTION) Tj")).toList(), true))
				.as("each page with lines repeats the table header").isGreaterThan(1);
	}

	@Test
	void numberFormats() {
		assertThat(Layout.amount("1234567.5000")).isEqualTo("1,234,567.50");
		assertThat(Layout.amount("-12.3456")).isEqualTo("-12.3456");
		assertThat(Layout.amount("0.0000")).isEqualTo("0.00");
		assertThat(Layout.quantity("2.0000")).isEqualTo("2");
		assertThat(Layout.quantity("8.2500")).isEqualTo("8.25");
		assertThat(Layout.address("1 Main St", null, "Dublin", null, "D01", "Ireland"))
				.containsExactly("1 Main St", "Dublin D01", "Ireland");
	}
}
