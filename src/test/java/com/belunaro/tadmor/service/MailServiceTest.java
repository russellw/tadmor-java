package com.belunaro.tadmor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailServiceTest {

	/** Captures what would be sent instead of talking to a server. */
	static class CapturingSender extends JavaMailSenderImpl {
		final List<MimeMessage> sent = new ArrayList<>();

		@Override
		protected void doSend(MimeMessage[] messages, Object[] originals) {
			sent.addAll(List.of(messages));
		}
	}

	private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		if (sender != null) {
			beans.addBean("mail", sender);
		}
		return beans.getBeanProvider(JavaMailSender.class);
	}

	private static final PrintService.Printed INVOICE = new PrintService.Printed("%PDF-1.4 x".getBytes(),
			"invoice-INV-1.pdf", "Invoice INV-1", "INV-1");

	@Test
	void sendsThePdfAsAnAttachment() throws Exception {
		CapturingSender sender = new CapturingSender();
		new MailService(provider(sender), "billing@ours.example").send(List.of("ap@customer.example"), INVOICE);

		assertThat(sender.sent).hasSize(1);
		MimeMessage m = sender.sent.get(0);
		m.saveChanges(); // as JavaMail's transport does before sending, which sets the parts' Content-Type headers
		assertThat(m.getSubject()).isEqualTo("Invoice INV-1");
		assertThat(((InternetAddress) m.getFrom()[0]).getAddress()).isEqualTo("billing@ours.example");
		assertThat(((InternetAddress) m.getAllRecipients()[0]).getAddress()).isEqualTo("ap@customer.example");
		Multipart parts = (Multipart) m.getContent();
		List<String> seen = new ArrayList<>();
		for (int i = 0; i < parts.getCount(); i++) {
			Part part = parts.getBodyPart(i);
			seen.add(part.getDisposition() + " " + part.getFileName() + " " + part.getContentType());
		}
		assertThat(seen).anySatisfy(p -> assertThat(p).startsWith("attachment invoice-INV-1.pdf application/pdf"));
	}

	@Test
	void disabledWithoutASender() {
		assertThatThrownBy(() -> new MailService(provider(null), "").send(List.of("a@b.example"), INVOICE))
				.isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED));
	}

	@Test
	void filenamesReplaceUnsafeCharacters() {
		assertThat(PrintService.filename(PrintService.Printable.SALES_INVOICE, "INV/2026 01"))
				.isEqualTo("invoice-INV-2026-01.pdf");
		assertThat(PrintService.filename(PrintService.Printable.PURCHASE_CREDIT_NOTE, "a.b_c-dé"))
				.isEqualTo("supplier-credit-a.b_c-d-.pdf");
	}
}
