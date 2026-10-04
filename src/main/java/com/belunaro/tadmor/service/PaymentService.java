package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer and supplier payments (spec/api.md §5.9): drafts, posting, and
 * unposting, with the same lifecycle as the other documents (spec/domain.md
 * §4). The schema refuses an amount that is not positive and an unknown
 * method (422).
 */
@Service
public class PaymentService {

	/** The two payment kinds, by their schema names. */
	public enum Kind {
		CUSTOMER("Customer payment", "customer_payments", "customer_id", "customers", "ar_account_id",
				"deposit_account_id", SettlerKind.CUSTOMER_PAYMENT),
		SUPPLIER("Supplier payment", "supplier_payments", "supplier_id", "suppliers", "ap_account_id",
				"payment_account_id", SettlerKind.SUPPLIER_PAYMENT);

		final String label;
		final String table;
		final String party;
		final String partyTable;
		final String control;
		/** The bank or cash account: debited by a customer payment, credited by a supplier payment. */
		final String account;
		final SettlerKind settler;

		Kind(String label, String table, String party, String partyTable, String control, String account,
				SettlerKind settler) {
			this.label = label;
			this.table = table;
			this.party = party;
			this.partyTable = partyTable;
			this.control = control;
			this.account = account;
			this.settler = settler;
		}

		public SettlerKind settler() {
			return settler;
		}
	}

	private final JdbcClient jdbc;
	private final Journal journal;
	private final SettlementService settlement;

	public PaymentService(JdbcClient jdbc, Journal journal, SettlementService settlement) {
		this.jdbc = jdbc;
		this.journal = journal;
		this.settlement = settlement;
	}

	// ---- request bodies, reduced to one internal form ----

	public record PaymentInput(Integer partyId, String date, String currencyCode, String amount, String method,
			String reference, Integer accountId) {
		public PaymentInput {
			date = trim(date);
			currencyCode = trim(currencyCode);
			amount = trim(amount);
			method = method == null || method.isBlank() ? null : method.strip();
		}
	}

	public interface PaymentRequest {
		PaymentInput payment();
	}

	public record CustomerPaymentInput(Integer customerId, String paymentDate, String currencyCode, String amount,
			String method, String reference, Integer depositAccountId) implements PaymentRequest {
		@Override
		public PaymentInput payment() {
			return new PaymentInput(customerId, paymentDate, currencyCode, amount, method, reference, depositAccountId);
		}
	}

	public record SupplierPaymentInput(Integer supplierId, String paymentDate, String currencyCode, String amount,
			String method, String reference, Integer paymentAccountId) implements PaymentRequest {
		@Override
		public PaymentInput payment() {
			return new PaymentInput(supplierId, paymentDate, currencyCode, amount, method, reference, paymentAccountId);
		}
	}

	// ---- read shapes ----

	public record CustomerPayment(int id, int customerId, String paymentDate, Integer depositAccountId,
			String currencyCode, String amount, String method, String reference, String status, String amountApplied,
			String unapplied, Integer journalEntryId) {
	}

	public record SupplierPayment(int id, int supplierId, String paymentDate, Integer paymentAccountId,
			String currencyCode, String amount, String method, String reference, String status, String amountApplied,
			String unapplied, Integer journalEntryId) {
	}

	private static String select(Kind k) {
		return "SELECT p.id, p." + k.party + ", p.payment_date::text AS payment_date, p." + k.account + """
				, p.currency_code, p.amount::text AS amount, p.method, p.reference, p.status,
				    a.applied::text AS amount_applied, (p.amount - a.applied)::text AS unapplied, p.journal_entry_id
				FROM """ + " " + k.table + " p CROSS JOIN LATERAL (SELECT COALESCE(sum(amount_applied), 0)::numeric(19,4) "
				+ "AS applied FROM " + k.settler.applications + " WHERE " + k.settler.settlerColumn + " = p.id) a";
	}

	/** Newest first, then by id descending. */
	public <T> List<T> list(Kind k, Class<T> shape) {
		return jdbc.sql(select(k) + " ORDER BY p.payment_date DESC, p.id DESC").query(shape).list();
	}

	public <T> T get(Kind k, int id, Class<T> shape) {
		return jdbc.sql(select(k) + " WHERE p.id = ?").param(id).query(shape).optional()
				.orElseThrow(ServiceException::notFound);
	}

	// ---- drafts ----

	public int create(Kind k, PaymentInput in) {
		validate(k, in);
		return jdbc.sql("INSERT INTO " + k.table + " (" + k.party + ", payment_date, currency_code, amount, method, "
				+ "reference, " + k.account + ") VALUES (:partyId, CAST(:date AS date), :currencyCode, "
				+ "CAST(:amount AS numeric), :method, :reference, :accountId) RETURNING id")
				.paramSource(in).query(Integer.class).single();
	}

	@Transactional
	public void update(Kind k, int id, PaymentInput in) {
		validate(k, in);
		requireDraft(k, id);
		jdbc.sql("UPDATE " + k.table + " SET " + k.party + " = :partyId, payment_date = CAST(:date AS date), "
				+ "currency_code = :currencyCode, amount = CAST(:amount AS numeric), method = :method, "
				+ "reference = :reference, " + k.account + " = :accountId WHERE id = :id")
				.paramSource(Params.of(in, "id", id)).update();
	}

	@Transactional
	public void delete(Kind k, int id) {
		requireDraft(k, id);
		jdbc.sql("DELETE FROM " + k.table + " WHERE id = ?").param(id).update();
	}

	// ---- posting ----

	/**
	 * Posts the payment (domain §4.2, §4.3): it must exist (404) and be a
	 * draft (409), and have a bank account and a party control account, an
	 * open period, and a rate (422). A customer payment debits the deposit
	 * account and credits A/R; a supplier payment debits A/P and credits the
	 * payment account; both lines at round(amount × rate, 4) in base.
	 */
	@Transactional
	public int post(Kind k, int id) {
		record Draft(String status, String date, String currencyCode, Integer bankAccountId, Integer controlAccountId) {
		}
		Draft d = jdbc.sql("SELECT p.status, p.payment_date::text AS date, p.currency_code, p." + k.account
				+ " AS bank_account_id, c." + k.control + " AS control_account_id FROM " + k.table + " p JOIN "
				+ k.partyTable + " c ON c.id = p." + k.party + " WHERE p.id = ? FOR UPDATE OF p")
				.param(id).query(Draft.class).optional().orElseThrow(ServiceException::notFound);
		if (!d.status().equals("draft")) {
			throw ServiceException.conflict(k.label + " " + id + " is " + d.status() + ", not a draft");
		}
		if (d.bankAccountId() == null) {
			throw ServiceException.unprocessable(k.label + " " + id + " has no " + k.account.replace("_id", ""));
		}
		if (d.controlAccountId() == null) {
			throw ServiceException.unprocessable("the " + (k == Kind.CUSTOMER ? "customer has no A/R" : "supplier has no A/P")
					+ " account");
		}
		int period = journal.periodFor(d.date());
		int entry = journal.createEntry(d.date(), period, d.currencyCode(), k.label, null);
		int debit = k == Kind.CUSTOMER ? d.bankAccountId() : d.controlAccountId();
		int credit = k == Kind.CUSTOMER ? d.controlAccountId() : d.bankAccountId();
		jdbc.sql("""
				INSERT INTO journal_lines (journal_entry_id, line_no, account_id, debit, credit, memo, base_debit, base_credit)
				SELECT :entry, 1, :debit, p.amount, 0, 'Payment', round(p.amount * e.exchange_rate, 4), 0
				FROM %1$s p, journal_entries e WHERE p.id = :id AND e.id = :entry
				UNION ALL
				SELECT :entry, 2, :credit, 0, p.amount, 'Payment', 0, round(p.amount * e.exchange_rate, 4)
				FROM %1$s p, journal_entries e WHERE p.id = :id AND e.id = :entry""".formatted(k.table))
				.param("entry", entry).param("debit", debit).param("credit", credit).param("id", id).update();
		jdbc.sql("UPDATE " + k.table + " SET status = 'posted', journal_entry_id = ?, period_id = ? WHERE id = ?")
				.params(entry, period, id).update();
		return entry;
	}

	/**
	 * Unposts the payment (administrators only): reverses its entry, removes
	 * its applications and reverses the FX entries they created, and returns
	 * it to draft (domain §4.4).
	 */
	@Transactional
	public int unpost(Kind k, int id) {
		record Posted(String status, Integer journalEntryId) {
		}
		Posted p = jdbc.sql("SELECT status, journal_entry_id FROM " + k.table + " WHERE id = ? FOR UPDATE").param(id)
				.query(Posted.class).optional().orElseThrow(ServiceException::notFound);
		if (!p.status().equals("posted") || p.journalEntryId() == null) {
			throw ServiceException.conflict(k.label + " " + id + " is " + p.status() + ", not posted");
		}
		int reversal = journal.reverse(p.journalEntryId());
		settlement.unwind(k.settler, id);
		jdbc.sql("UPDATE " + k.table + " SET status = 'draft', journal_entry_id = NULL, period_id = NULL WHERE id = ?")
				.param(id).update();
		return reversal;
	}

	/** 400 for a missing party, date, currency, or amount (spec/api.md §5.9). */
	private static void validate(Kind k, PaymentInput in) {
		if (in.partyId() == null || in.partyId() <= 0) {
			throw ServiceException.badRequest(k.party + " is required");
		}
		require(in.date(), "payment_date");
		require(in.currencyCode(), "currency_code");
		require(in.amount(), "amount");
	}

	private void requireDraft(Kind k, int id) {
		String status = jdbc.sql("SELECT status FROM " + k.table + " WHERE id = ? FOR UPDATE").param(id)
				.query(String.class).optional().orElseThrow(ServiceException::notFound);
		if (!status.equals("draft")) {
			throw ServiceException.conflict(k.label + " " + id + " is " + status + ", not a draft");
		}
	}
}
