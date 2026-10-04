package com.belunaro.tadmor.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Journal reads and reports (spec/api.md §5.14). Report figures cover posted entries only, in base currency. */
@Service
public class LedgerService {

	private final JdbcClient jdbc;

	public LedgerService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * One posted journal line on an account. The memo is the line's own,
	 * falling back to the entry's. Debit and credit are in the entry's
	 * currency; the base amounts are what the account's balance sums.
	 */
	public record LedgerRow(int journalEntryId, String entryDate, String reference, String memo, String currencyCode,
			String debit, String credit, String baseDebit, String baseCredit) {
	}

	/** An account's posted lines within inclusive optional date bounds, in entry order. */
	public List<LedgerRow> accountLedger(int accountId, LocalDate from, LocalDate to) {
		if (jdbc.sql("SELECT count(*) FROM accounts WHERE id = ?").param(accountId).query(Integer.class).single() == 0) {
			throw ServiceException.notFound();
		}
		return jdbc.sql("""
				SELECT je.id AS journal_entry_id, je.entry_date::text AS entry_date, je.reference,
				    COALESCE(jl.memo, je.memo) AS memo, je.currency_code,
				    jl.debit::numeric(19,4)::text AS debit, jl.credit::numeric(19,4)::text AS credit,
				    jl.base_debit::numeric(19,4)::text AS base_debit, jl.base_credit::numeric(19,4)::text AS base_credit
				FROM journal_lines jl
				JOIN journal_entries je ON je.id = jl.journal_entry_id
				WHERE jl.account_id = :account
				  AND je.status = 'posted'
				  AND (CAST(:from AS date) IS NULL OR je.entry_date >= CAST(:from AS date))
				  AND (CAST(:to AS date) IS NULL OR je.entry_date <= CAST(:to AS date))
				ORDER BY je.entry_date, je.id, jl.line_no""")
				.param("account", accountId)
				.param("from", from)
				.param("to", to)
				.query(LedgerRow.class)
				.list();
	}

	/** A journal entry with its lines, in line order; amounts in the entry's currency and in base. */
	public record JournalEntry(int id, String entryDate, String currencyCode, String exchangeRate, String reference,
			String memo, String status, List<JournalLine> lines) {
	}

	public record JournalLine(int lineNo, int accountId, String accountCode, String accountName, String memo,
			String debit, String credit, String baseDebit, String baseCredit) {
	}

	private record EntryHeader(int id, String entryDate, String currencyCode, String exchangeRate, String reference,
			String memo, String status) {
	}

	public JournalEntry journalEntry(int id) {
		EntryHeader e = jdbc.sql("""
				SELECT id, entry_date::text AS entry_date, currency_code, trim_scale(exchange_rate)::text AS exchange_rate,
				    reference, memo, status
				FROM journal_entries WHERE id = ?""").param(id).query(EntryHeader.class).optional()
				.orElseThrow(ServiceException::notFound);
		List<JournalLine> lines = jdbc.sql("""
				SELECT jl.line_no, jl.account_id, a.code AS account_code, a.name AS account_name, jl.memo,
				    jl.debit::text AS debit, jl.credit::text AS credit,
				    jl.base_debit::text AS base_debit, jl.base_credit::text AS base_credit
				FROM journal_lines jl JOIN accounts a ON a.id = jl.account_id
				WHERE jl.journal_entry_id = ? ORDER BY jl.line_no""").param(id).query(JournalLine.class).list();
		return new JournalEntry(e.id(), e.entryDate(), e.currencyCode(), e.exchangeRate(), e.reference(), e.memo(),
				e.status(), lines);
	}

	/** Every account, active or not and with or without activity, in base amounts; the balance is debit-positive. */
	public record TrialBalanceRow(int accountId, String code, String name, String accountType, String totalDebit,
			String totalCredit, String balance) {
	}

	public List<TrialBalanceRow> trialBalance() {
		return jdbc.sql("""
				SELECT account_id, code, name, account_type, total_debit::numeric(19,4)::text AS total_debit,
				    total_credit::numeric(19,4)::text AS total_credit, balance::numeric(19,4)::text AS balance
				FROM trial_balance ORDER BY code""").query(TrialBalanceRow.class).list();
	}

	/** Each product with movements, valued at moving-average cost in base currency. */
	public record ValuationRow(int productId, String sku, String name, String qtyOnHand, String valueOnHand,
			String avgUnitCost) {
	}

	public List<ValuationRow> inventoryValuation() {
		return jdbc.sql("""
				SELECT v.product_id, p.sku, p.name, v.qty_on_hand::numeric(19,4)::text AS qty_on_hand,
				    v.value_on_hand::numeric(19,4)::text AS value_on_hand, v.avg_unit_cost::numeric(19,4)::text AS avg_unit_cost
				FROM stock_valuation v JOIN products p ON p.id = v.product_id
				ORDER BY p.sku""").query(ValuationRow.class).list();
	}
}
