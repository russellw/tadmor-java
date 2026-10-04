package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.MailService;
import com.belunaro.tadmor.service.PrintService;
import com.belunaro.tadmor.service.PrintService.Printable;
import com.belunaro.tadmor.service.PrintService.Printed;
import com.belunaro.tadmor.service.ServiceException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Printing and emailing documents (spec/api.md §5.11), for the six printable collections. */
@RestController
@RequestMapping("/api/{collection:sales-invoices|purchase-bills|sales-credit-notes|purchase-credit-notes|sales-orders|purchase-orders}")
public class PrintController {

	private final PrintService printing;
	private final MailService mail;

	public PrintController(PrintService printing, MailService mail) {
		this.printing = printing;
		this.mail = mail;
	}

	private static Printable printable(String collection) {
		return Printable.ofCollection(collection).orElseThrow(ServiceException::notFound);
	}

	@GetMapping("/{id}/pdf")
	public ResponseEntity<byte[]> pdf(@PathVariable String collection, @PathVariable Id id) {
		Printed doc = printing.print(printable(collection), id.value());
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.filename() + "\"")
				.body(doc.pdf());
	}

	public record EmailRequest(List<String> to) {
	}

	/**
	 * Emails the PDF. With no recipients the counterparty organization's email
	 * is used, and without one that is a 422; with email disabled, a 501.
	 */
	@PostMapping("/{id}/email")
	public ResponseEntity<Map<String, Object>> email(@PathVariable String collection, @PathVariable Id id,
			@RequestBody(required = false) EmailRequest in) {
		Printable p = printable(collection);
		List<String> to = in == null || in.to() == null ? List.of()
				: in.to().stream().filter(t -> t != null && !t.isBlank()).map(String::strip).toList();
		if (to.isEmpty()) {
			to = List.of(printing.recipient(p, id.value()).orElseThrow(() -> ServiceException.unprocessable(
					"this counterparty has no email address on file; supply a recipient or set one on the organization")));
		}
		mail.send(to, printing.print(p, id.value()));
		return ResponseEntity.status(HttpStatus.OK).body(Map.of("status", "sent", "to", to));
	}
}
