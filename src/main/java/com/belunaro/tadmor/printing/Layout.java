package com.belunaro.tadmor.printing;

import static com.belunaro.tadmor.printing.Pdf.Font.HELVETICA;
import static com.belunaro.tadmor.printing.Pdf.Font.HELVETICA_BOLD;

import java.util.ArrayList;
import java.util.List;

/**
 * The one layout every printable document shares (spec/domain.md §11),
 * ported from tadmor's internal/printing: an A4 page with the title and
 * key facts top right, the issuer and counterparty blocks, the line table
 * (continued on further pages as needed), totals, and notes, with a page
 * footer.
 */
public final class Layout {

	private Layout() {
	}

	private static final double PAGE_W = 595.28;
	private static final double PAGE_H = 841.89;
	private static final double MARGIN_L = 54;
	private static final double RIGHT_X = PAGE_W - 54;
	private static final double TOP_Y = PAGE_H - 54;
	private static final double BOTTOM_Y = 72; // content stops here; the footer sits below

	private static final double NUM_X = MARGIN_L;
	private static final double DESC_X = MARGIN_L + 26;
	private static final double QTY_X = 360;
	private static final double PRICE_X = 432;
	private static final double TAX_X = 472;
	private static final double AMOUNT_X = RIGHT_X;
	private static final double DESC_MAX = QTY_X - 60 - DESC_X;

	private static final double GRAY = 0.45; // secondary text

	public record Party(String name, String legalName, String taxId, List<String> address) {
	}

	public record Line(int lineNo, String description, String quantity, String unit, String taxRate, String subtotal) {
	}

	public record Meta(String label, String value) {
	}

	/**
	 * A document to print. The applied amount and balance are shown only when
	 * the kind has them (appliedLabel set) and something is applied.
	 */
	public record Document(String kind, String number, String status, String currency, List<Meta> meta, String partyLabel,
			Party party, Party seller, String unitLabel, String subtotal, String taxTotal, String total, String applied,
			String balance, String appliedLabel, String balanceLabel, String reference, String memo, List<Line> lines) {

		String title() {
			String t = kind.toUpperCase();
			return switch (status) {
			case "draft" -> "DRAFT " + t;
			case "void" -> "VOID " + t;
			case "cancelled" -> "CANCELLED " + t;
			default -> t;
			};
		}
	}

	public static byte[] render(Document d) {
		Pdf pdf = new Pdf();
		Pdf.Page p = pdf.addPage(PAGE_W, PAGE_H);

		String title = d.title();
		p.text(HELVETICA_BOLD, 20, RIGHT_X - Pdf.width(HELVETICA_BOLD, 20, title), TOP_Y - 6, title);
		double metaY = TOP_Y - 36;
		for (Meta m : d.meta()) {
			p.textGray(HELVETICA, 9, RIGHT_X - 140, metaY, GRAY, m.label());
			p.text(HELVETICA, 9, RIGHT_X - Pdf.width(HELVETICA, 9, m.value()), metaY, m.value());
			metaY -= 13;
		}

		double y = TOP_Y - 6;
		if (d.seller() != null) {
			y = party(p, MARGIN_L, y, 11, d.seller());
		}
		y = Math.min(y, metaY) - 28;
		p.textGray(HELVETICA_BOLD, 8, MARGIN_L, y, GRAY, d.partyLabel());
		y = party(p, MARGIN_L, y - 14, 10, d.party());

		y = tableHeader(p, y - 24, d.unitLabel());
		for (Line l : d.lines()) {
			if (y < BOTTOM_Y + 20) {
				p = pdf.addPage(PAGE_W, PAGE_H);
				y = tableHeader(p, TOP_Y, d.unitLabel());
			}
			p.textGray(HELVETICA, 9, NUM_X, y, GRAY, Integer.toString(l.lineNo()));
			p.text(HELVETICA, 9, DESC_X, y, truncate(HELVETICA, 9, DESC_MAX, l.description()));
			rightAligned(p, QTY_X, y, quantity(l.quantity()));
			rightAligned(p, PRICE_X, y, amount(l.unit()));
			rightAligned(p, TAX_X, y, quantity(l.taxRate()));
			rightAligned(p, AMOUNT_X, y, amount(l.subtotal()));
			y -= 6;
			p.line(MARGIN_L, y, RIGHT_X, y, 0.4, 0.9);
			y -= 12;
		}

		if (y < BOTTOM_Y + 110) {
			p = pdf.addPage(PAGE_W, PAGE_H);
			y = TOP_Y;
		}
		Totals totals = new Totals(p, y - 8);
		totals.add("Subtotal", amount(d.subtotal()), HELVETICA);
		totals.add("Tax", amount(d.taxTotal()), HELVETICA);
		p.line(Totals.X, totals.y + 9, RIGHT_X, totals.y + 9, 0.8, 0.2);
		totals.y -= 2;
		totals.add("Total", d.currency() + " " + amount(d.total()), HELVETICA_BOLD);
		if (d.appliedLabel() != null && d.applied() != null && !amount(d.applied()).equals("0.00")) {
			totals.add(d.appliedLabel(), amount(d.applied()), HELVETICA);
			totals.add(d.balanceLabel(), d.currency() + " " + amount(d.balance()), HELVETICA_BOLD);
		}

		double noteY = totals.y - 14;
		if (d.reference() != null && !d.reference().isEmpty()) {
			p.textGray(HELVETICA, 9, MARGIN_L, noteY, GRAY, "Reference: " + d.reference());
			noteY -= 13;
		}
		if (d.memo() != null && !d.memo().isEmpty()) {
			for (String line : wrap(HELVETICA, 9, RIGHT_X - MARGIN_L, d.memo())) {
				p.textGray(HELVETICA, 9, MARGIN_L, noteY, GRAY, line);
				noteY -= 13;
			}
		}

		List<Pdf.Page> pages = pdf.pages();
		for (int i = 0; i < pages.size(); i++) {
			String footer = d.kind() + " " + d.number() + "  ·  Page " + (i + 1) + " of " + pages.size();
			pages.get(i).textGray(HELVETICA, 8, (PAGE_W - Pdf.width(HELVETICA, 8, footer)) / 2, 40, GRAY, footer);
		}
		return pdf.bytes();
	}

	/** The totals block, a column of label and right-aligned value rows. */
	private static final class Totals {
		static final double X = 400;
		private final Pdf.Page page;
		double y;

		Totals(Pdf.Page page, double y) {
			this.page = page;
			this.y = y;
		}

		void add(String label, String value, Pdf.Font font) {
			page.text(font, 9, X, y, label);
			page.text(font, 9, RIGHT_X - Pdf.width(font, 9, value), y, value);
			y -= 14;
		}
	}

	private static void rightAligned(Pdf.Page p, double x, double y, String s) {
		p.text(HELVETICA, 9, x - Pdf.width(HELVETICA, 9, s), y, s);
	}

	private static double party(Pdf.Page p, double x, double y, double nameSize, Party b) {
		p.text(HELVETICA_BOLD, nameSize, x, y, b.name());
		y -= 13;
		if (b.legalName() != null && !b.legalName().isEmpty() && !b.legalName().equals(b.name())) {
			p.textGray(HELVETICA, 9, x, y, GRAY, b.legalName());
			y -= 12;
		}
		for (String line : b.address()) {
			p.textGray(HELVETICA, 9, x, y, GRAY, line);
			y -= 12;
		}
		if (b.taxId() != null && !b.taxId().isEmpty()) {
			p.textGray(HELVETICA, 9, x, y, GRAY, "Tax ID: " + b.taxId());
			y -= 12;
		}
		return y;
	}

	private static double tableHeader(Pdf.Page p, double y, String unitLabel) {
		p.textGray(HELVETICA_BOLD, 8, NUM_X, y, GRAY, "#");
		p.textGray(HELVETICA_BOLD, 8, DESC_X, y, GRAY, "DESCRIPTION");
		headingRight(p, QTY_X, y, "QTY");
		headingRight(p, PRICE_X, y, unitLabel);
		headingRight(p, TAX_X, y, "TAX %");
		headingRight(p, AMOUNT_X, y, "AMOUNT");
		y -= 6;
		p.line(MARGIN_L, y, RIGHT_X, y, 0.8, 0.2);
		return y - 14;
	}

	private static void headingRight(Pdf.Page p, double x, double y, String label) {
		p.textGray(HELVETICA_BOLD, 8, x - Pdf.width(HELVETICA_BOLD, 8, label), y, GRAY, label);
	}

	/** An address as printed lines: the street lines, "city, region postal", and the country. */
	public static List<String> address(String line1, String line2, String city, String region, String postal, String country) {
		List<String> out = new ArrayList<>();
		for (String l : new String[] { line1, line2 }) {
			if (l != null && !l.isEmpty()) {
				out.add(l);
			}
		}
		StringBuilder cityLine = new StringBuilder(city == null ? "" : city);
		if (region != null && !region.isEmpty()) {
			cityLine.append(cityLine.isEmpty() ? "" : ", ").append(region);
		}
		if (postal != null && !postal.isEmpty()) {
			cityLine.append(" ").append(postal);
		}
		if (!cityLine.isEmpty()) {
			out.add(cityLine.toString().strip());
		}
		if (country != null && !country.isEmpty()) {
			out.add(country);
		}
		return out;
	}

	private static String truncate(Pdf.Font f, double size, double max, String s) {
		if (Pdf.width(f, size, s) <= max) {
			return s;
		}
		int[] cps = s.codePoints().toArray();
		int n = cps.length;
		while (n > 0 && Pdf.width(f, size, new String(cps, 0, n) + "…") > max) {
			n--;
		}
		return new String(cps, 0, n) + "…";
	}

	private static List<String> wrap(Pdf.Font f, double size, double max, String s) {
		List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : s.strip().split("\\s+")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (Pdf.width(f, size, candidate) > max && !line.isEmpty()) {
				lines.add(line);
				line = word;
			} else {
				line = candidate;
			}
		}
		if (!line.isEmpty()) {
			lines.add(line);
		}
		return lines;
	}

	/** A money amount with thousands separators and at least two decimals: "1,234.50". */
	static String amount(String s) {
		String sign = "";
		if (s.startsWith("-")) {
			sign = "-";
			s = s.substring(1);
		}
		int dot = s.indexOf('.');
		String integer = dot < 0 ? s : s.substring(0, dot);
		String fraction = dot < 0 ? "" : s.substring(dot + 1).replaceAll("0+$", "");
		while (fraction.length() < 2) {
			fraction += "0";
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
	static String quantity(String s) {
		return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
	}
}
