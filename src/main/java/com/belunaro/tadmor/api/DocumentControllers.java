package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.DocumentKind;
import com.belunaro.tadmor.service.DocumentService;
import com.belunaro.tadmor.service.DocumentService.BillLine;
import com.belunaro.tadmor.service.DocumentService.InvoiceLine;
import com.belunaro.tadmor.service.DocumentService.PurchaseBill;
import com.belunaro.tadmor.service.DocumentService.PurchaseBillInput;
import com.belunaro.tadmor.service.DocumentService.PurchaseCreditNote;
import com.belunaro.tadmor.service.DocumentService.PurchaseCreditNoteInput;
import com.belunaro.tadmor.service.DocumentService.SalesCreditNote;
import com.belunaro.tadmor.service.DocumentService.SalesCreditNoteInput;
import com.belunaro.tadmor.service.DocumentService.SalesInvoice;
import com.belunaro.tadmor.service.DocumentService.SalesInvoiceInput;
import com.belunaro.tadmor.service.DocumentService.DocumentRequest;
import com.belunaro.tadmor.service.PostingService;
import com.belunaro.tadmor.service.SettlementService;
import com.belunaro.tadmor.service.SettlementService.Applied;
import com.belunaro.tadmor.service.SettlementService.Application;
import com.belunaro.tadmor.service.SettlerKind;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The document collections (spec/api.md §5.9), each a LineDocumentController. */
final class DocumentControllers {

	private DocumentControllers() {
	}

	@RestController
	@RequestMapping("/api/sales-invoices")
	static class SalesInvoices extends LineDocumentController<SalesInvoiceInput, SalesInvoice, InvoiceLine> {
		SalesInvoices(DocumentService documents, PostingService posting) {
			super(DocumentKind.SALES_INVOICE, SalesInvoice.class, InvoiceLine.class, documents, posting);
		}
	}

	@RestController
	@RequestMapping("/api/purchase-bills")
	static class PurchaseBills extends LineDocumentController<PurchaseBillInput, PurchaseBill, BillLine> {
		PurchaseBills(DocumentService documents, PostingService posting) {
			super(DocumentKind.PURCHASE_BILL, PurchaseBill.class, BillLine.class, documents, posting);
		}
	}

	/** Credit notes are documents with lines that also settle invoices or bills by application. */
	abstract static class CreditNoteController<I extends DocumentRequest, D, L> extends LineDocumentController<I, D, L> {

		private final SettlerKind settler;
		private final SettlementService settlement;

		CreditNoteController(DocumentKind kind, SettlerKind settler, Class<D> shape, Class<L> lineShape,
				DocumentService documents, PostingService posting, SettlementService settlement) {
			super(kind, shape, lineShape, documents, posting);
			this.settler = settler;
			this.settlement = settlement;
		}

		@PostMapping("/{id}/apply")
		public Map<String, List<Applied>> apply(@PathVariable Id id) {
			return Map.of("applications", settlement.apply(settler, id.value()));
		}

		@GetMapping("/{id}/applications")
		public List<Application> applications(@PathVariable Id id) {
			return settlement.applications(settler, id.value());
		}
	}

	@RestController
	@RequestMapping("/api/sales-credit-notes")
	static class SalesCreditNotes extends CreditNoteController<SalesCreditNoteInput, SalesCreditNote, InvoiceLine> {
		SalesCreditNotes(DocumentService documents, PostingService posting, SettlementService settlement) {
			super(DocumentKind.SALES_CREDIT_NOTE, SettlerKind.SALES_CREDIT_NOTE, SalesCreditNote.class, InvoiceLine.class,
					documents, posting, settlement);
		}
	}

	@RestController
	@RequestMapping("/api/purchase-credit-notes")
	static class PurchaseCreditNotes extends CreditNoteController<PurchaseCreditNoteInput, PurchaseCreditNote, BillLine> {
		PurchaseCreditNotes(DocumentService documents, PostingService posting, SettlementService settlement) {
			super(DocumentKind.PURCHASE_CREDIT_NOTE, SettlerKind.PURCHASE_CREDIT_NOTE, PurchaseCreditNote.class,
					BillLine.class, documents, posting, settlement);
		}
	}
}
