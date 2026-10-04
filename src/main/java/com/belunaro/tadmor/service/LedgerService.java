package com.belunaro.tadmor.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Journal reads (spec/api.md §5.14). Figures cover posted entries only. */
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
}
