package com.belunaro.tadmor.service;

import java.util.List;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Emailing printed documents (spec/api.md §5.11) through Spring's
 * JavaMailSender. Sending is enabled only when SMTP_ADDR is set (see
 * TadmorApplication); otherwise there is no sender, and sending is refused
 * with 501, as the conformance configuration expects.
 */
@Service
public class MailService {

	private final ObjectProvider<JavaMailSender> sender;
	private final String from;

	public MailService(ObjectProvider<JavaMailSender> sender, @Value("${tadmor.mail-from:}") String from) {
		this.sender = sender;
		this.from = from;
	}

	public void send(List<String> to, PrintService.Printed doc) {
		JavaMailSender mail = sender.getIfAvailable();
		if (mail == null) {
			throw new ServiceException(HttpStatus.NOT_IMPLEMENTED, "email sending is not configured on this server");
		}
		try {
			MimeMessage message = mail.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			if (!from.isBlank()) {
				helper.setFrom(from);
			}
			helper.setTo(to.toArray(String[]::new));
			helper.setSubject(doc.subject());
			helper.setText("Please find attached " + doc.subject().toLowerCase() + ".");
			helper.addAttachment(doc.filename(), new ByteArrayResource(doc.pdf()), "application/pdf");
			mail.send(message);
		} catch (MessagingException | MailException e) {
			throw new ServiceException(HttpStatus.BAD_GATEWAY, "the email could not be sent: " + e.getMessage());
		}
	}
}
