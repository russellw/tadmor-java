package com.belunaro.tadmor.api;

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
import com.belunaro.tadmor.service.PostingService;

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

	@RestController
	@RequestMapping("/api/sales-credit-notes")
	static class SalesCreditNotes
			extends LineDocumentController<SalesCreditNoteInput, SalesCreditNote, InvoiceLine> {
		SalesCreditNotes(DocumentService documents, PostingService posting) {
			super(DocumentKind.SALES_CREDIT_NOTE, SalesCreditNote.class, InvoiceLine.class, documents, posting);
		}
	}

	@RestController
	@RequestMapping("/api/purchase-credit-notes")
	static class PurchaseCreditNotes
			extends LineDocumentController<PurchaseCreditNoteInput, PurchaseCreditNote, BillLine> {
		PurchaseCreditNotes(DocumentService documents, PostingService posting) {
			super(DocumentKind.PURCHASE_CREDIT_NOTE, PurchaseCreditNote.class, BillLine.class, documents, posting);
		}
	}
}
