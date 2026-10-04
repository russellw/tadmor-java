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
		app.setDefaultProperties(listenProperties(System.getenv("HTTP_ADDR"), System.getenv("PORT")));
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
}
