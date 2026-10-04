package com.belunaro.tadmor.printing;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * A minimal PDF writer, enough for printable business documents, ported from
 * tadmor's internal/pdf. It needs nothing beyond the JDK: pages use the
 * standard-14 Helvetica fonts, which every conforming reader has built in,
 * so no font program is embedded, only the advance widths for measuring
 * text (HelveticaMetrics).
 *
 * <p>Coordinates are PDF-native: points (1/72 inch) from the bottom-left of
 * the page, y increasing upward. Text is WinAnsi (windows-1252); characters
 * outside it render as '?'.
 */
final class Pdf {

	enum Font {
		HELVETICA("/F1", HelveticaMetrics.HELVETICA), HELVETICA_BOLD("/F2", HelveticaMetrics.HELVETICA_BOLD);

		private final String resource;
		private final int[] widths;

		Font(String resource, int[] widths) {
			this.resource = resource;
			this.widths = widths;
		}
	}

	private static final Charset WIN_ANSI = Charset.forName("windows-1252");

	/** The rendered width of the text, in points. */
	static double width(Font font, double size, String text) {
		int units = 0;
		for (byte b : encode(text)) {
			units += font.widths[b & 0xFF];
		}
		return units * size / 1000;
	}

	private final List<Page> pages = new ArrayList<>();

	Page addPage(double width, double height) {
		Page p = new Page(width, height);
		pages.add(p);
		return p;
	}

	List<Page> pages() {
		return pages;
	}

	static final class Page {
		private final double width;
		private final double height;
		private final ByteArrayOutputStream content = new ByteArrayOutputStream();

		private Page(double width, double height) {
			this.width = width;
			this.height = height;
		}

		void text(Font font, double size, double x, double y, String s) {
			textGray(font, size, x, y, 0, s);
		}

		void textGray(Font font, double size, double x, double y, double gray, String s) {
			write("BT " + font.resource + " " + num(size) + " Tf " + num(gray) + " g " + num(x) + " " + num(y) + " Td ");
			content.writeBytes(escape(encode(s)));
			write(" Tj ET\n");
		}

		void line(double x1, double y1, double x2, double y2, double lineWidth, double gray) {
			write(num(lineWidth) + " w " + num(gray) + " G " + num(x1) + " " + num(y1) + " m " + num(x2) + " " + num(y2)
					+ " l S\n");
		}

		private void write(String s) {
			content.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
		}
	}

	/** The shortest decimal that is exactly the value, as PDF wants numbers. */
	private static String num(double v) {
		return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
	}

	/** WinAnsi bytes; unmappable characters become '?'. */
	private static byte[] encode(String s) {
		return s.getBytes(WIN_ANSI);
	}

	private static byte[] escape(byte[] text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(text.length + 2);
		out.write('(');
		for (byte c : text) {
			switch (c) {
			case '(', ')', '\\' -> {
				out.write('\\');
				out.write(c);
			}
			case '\n' -> out.writeBytes(new byte[] { '\\', 'n' });
			case '\r' -> out.writeBytes(new byte[] { '\\', 'r' });
			default -> out.write(c);
			}
		}
		out.write(')');
		return out.toByteArray();
	}

	/** The document, with each page's content stream zlib-compressed. */
	byte[] bytes() {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		List<Integer> offsets = new ArrayList<>();
		ascii(buf, "%PDF-1.4\n");
		buf.writeBytes(new byte[] { '%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n' }); // binary marker

		StringBuilder kids = new StringBuilder();
		for (int i = 0; i < pages.size(); i++) {
			kids.append(5 + 2 * i).append(" 0 R ");
		}
		object(buf, offsets, "<< /Type /Catalog /Pages 2 0 R >>");
		object(buf, offsets, "<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");
		object(buf, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
		object(buf, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
		for (int i = 0; i < pages.size(); i++) {
			Page p = pages.get(i);
			object(buf, offsets, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(p.width) + " " + num(p.height)
					+ "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (6 + 2 * i) + " 0 R >>");
			byte[] compressed = deflate(p.content.toByteArray());
			offsets.add(buf.size());
			ascii(buf, offsets.size() + " 0 obj\n<< /Length " + compressed.length + " /Filter /FlateDecode >>\nstream\n");
			buf.writeBytes(compressed);
			ascii(buf, "\nendstream\nendobj\n");
		}

		int xref = buf.size();
		StringBuilder table = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
		for (int offset : offsets) {
			table.append(String.format("%010d 00000 n \n", offset));
		}
		table.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref)
				.append("\n%%EOF\n");
		ascii(buf, table.toString());
		return buf.toByteArray();
	}

	private static void object(ByteArrayOutputStream buf, List<Integer> offsets, String body) {
		offsets.add(buf.size());
		ascii(buf, offsets.size() + " 0 obj\n" + body + "\nendobj\n");
	}

	private static void ascii(ByteArrayOutputStream buf, String s) {
		buf.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] deflate(byte[] data) {
		Deflater deflater = new Deflater();
		deflater.setInput(data);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		while (!deflater.finished()) {
			out.write(chunk, 0, deflater.deflate(chunk));
		}
		deflater.end();
		return out.toByteArray();
	}
}
