package com.belunaro.tadmor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TadmorApplication {

	public static void main(String[] args) throws Exception {
		// "Today" is the UTC date (spec/api.md §1.2), here as in the database.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		if (args.length > 0) {
			// Out-of-band commands; anything else is passed to Spring.
			switch (args[0]) {
			case "adduser" -> {
				AddUser.main(Arrays.copyOfRange(args, 1, args.length));
				return;
			}
			case "resetdb" -> {
				ResetDb.main(Arrays.copyOfRange(args, 1, args.length));
				return;
			}
			default -> {
			}
			}
		}
		SpringApplication app = new SpringApplication(TadmorApplication.class);
		Map<String, Object> defaults = listenProperties(System.getenv("HTTP_ADDR"), System.getenv("PORT"));
		defaults.putAll(mailProperties(System.getenv("SMTP_ADDR"), System.getenv("SMTP_USER"), System.getenv("SMTP_PASS")));
		app.setDefaultProperties(defaults);
		app.run(args);
	}

	/**
	 * Maps tadmor's listen variables onto Spring's: HTTP_ADDR is host:port
	 * (default ":8080", every interface), and PORT, when set, overrides the
	 * port, as Cloud Run injects it.
	 */
	static Map<String, Object> listenProperties(String httpAddr, String port) {
		String addr = httpAddr == null || httpAddr.isBlank() ? ":8080" : httpAddr.trim();
		int colon = addr.lastIndexOf(':');
		if (colon < 0) {
			throw new IllegalArgumentException("HTTP_ADDR must be host:port, got " + addr);
		}
		Map<String, Object> props = new HashMap<>();
		String host = addr.substring(0, colon);
		if (!host.isEmpty()) {
			props.put("server.address", host);
		}
		props.put("server.port", port == null || port.isBlank() ? addr.substring(colon + 1) : port.trim());
		return props;
	}

	/**
	 * Maps tadmor's mail variables onto Spring's: SMTP_ADDR is host:port, and
	 * with it unset there is no mail sender and email is disabled (501).
	 * SMTP_USER and SMTP_PASS authenticate when set; STARTTLS is used when the
	 * server offers it, as tadmor's mailer does.
	 */
	static Map<String, Object> mailProperties(String smtpAddr, String user, String password) {
		Map<String, Object> props = new HashMap<>();
		if (smtpAddr == null || smtpAddr.isBlank()) {
			return props;
		}
		String addr = smtpAddr.strip();
		int colon = addr.lastIndexOf(':');
		props.put("spring.mail.host", colon < 0 ? addr : addr.substring(0, colon));
		props.put("spring.mail.port", colon < 0 ? "25" : addr.substring(colon + 1));
		props.put("spring.mail.properties.mail.smtp.starttls.enable", "true");
		if (user != null && !user.isBlank()) {
			props.put("spring.mail.username", user);
			props.put("spring.mail.password", password == null ? "" : password);
			props.put("spring.mail.properties.mail.smtp.auth", "true");
		}
		return props;
	}
}
