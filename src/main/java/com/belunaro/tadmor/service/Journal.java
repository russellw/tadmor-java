package com.belunaro.tadmor.service;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The journal machinery every posting shares: finding the accounting period
 * for a date (spec/domain.md §9.2), opening an entry at the right exchange
 * rate (§7.1), and reversing an entry (§4.4). Callers run inside their own
 * transaction, so a refusal part way leaves nothing behind, not even a
 * period created for the attempt.
 *
 * <p>The schema checks every entry at commit: it must balance in both its
 * own and the base currency, touch only postable, active accounts, and lie
 * in an open period.
 */
@Component
public class Journal {

	private final JdbcClient jdbc;

	public Journal(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The open period covering the date. If none covers it but an open
	 * fiscal year does, that calendar month's period is created, named
	 * YYYY-MM and clipped to the year. A closed period covering the date,
	 * no fiscal year, or a new period that would overlap another, is a 422.
	 */
	public int periodFor(String date) {
		Optional<Integer> open = openPeriod(date);
		if (open.isPresent()) {
			return open.get();
		}
		boolean covered = jdbc.sql("SELECT count(*) FROM accounting_periods WHERE CAST(? AS date) BETWEEN start_date AND end_date")
				.param(date).query(Integer.class).single() > 0;
		if (covered) {
			throw noOpenPeriod(date);
		}
		// ON CONFLICT DO NOTHING also absorbs the no-overlap exclusion constraint.
		jdbc.sql("""
				INSERT INTO accounting_periods (fiscal_year_id, name, start_date, end_date)
				SELECT fy.id, to_char(CAST(:date AS date), 'YYYY-MM'),
				    GREATEST(date_trunc('month', CAST(:date AS date))::date, fy.start_date),
				    LEAST((date_trunc('month', CAST(:date AS date)) + interval '1 month - 1 day')::date, fy.end_date)
				FROM fiscal_years fy
				WHERE CAST(:date AS date) BETWEEN fy.start_date AND fy.end_date AND fy.status = 'open'
				ORDER BY fy.start_date LIMIT 1
				ON CONFLICT DO NOTHING""").param("date", date).update();
		return openPeriod(date).orElseThrow(() -> noOpenPeriod(date));
	}

	private Optional<Integer> openPeriod(String date) {
		return jdbc.sql("""
				SELECT id FROM accounting_periods
				WHERE CAST(? AS date) BETWEEN start_date AND end_date AND status = 'open'
				ORDER BY id LIMIT 1""").param(date).query(Integer.class).optional();
	}

	private static ServiceException noOpenPeriod(String date) {
		return ServiceException.unprocessable("no open accounting period covers " + date);
	}

	/**
	 * Opens a posted entry, without lines, and returns its id. The rate is 1
	 * for the base currency, else the currency's latest rate dated on or
	 * before the entry (422 if there is none); the entry keeps it, so later
	 * rate edits never change history.
	 */
	public int createEntry(String date, int periodId, String currency, String memo, String reference) {
		return jdbc.sql("""
				INSERT INTO journal_entries (entry_date, period_id, currency_code, exchange_rate, memo, reference,
				    status, posted_at)
				SELECT CAST(:date AS date), :period, :currency, r.rate, :memo, :reference, 'posted', now()
				FROM (SELECT CASE WHEN :currency = (SELECT base_currency FROM gl_settings) THEN 1::numeric
				                  ELSE (SELECT rate FROM exchange_rates
				                        WHERE currency_code = :currency AND rate_date <= CAST(:date AS date)
				                        ORDER BY rate_date DESC LIMIT 1)
				             END AS rate) r
				WHERE r.rate IS NOT NULL
				RETURNING id""")
				.param("date", date).param("period", periodId).param("currency", currency)
				.param("memo", memo).param("reference", reference)
				.query(Integer.class).optional()
				.orElseThrow(() -> ServiceException.unprocessable(
						"no exchange rate for " + currency + " on or before " + date));
	}

	/**
	 * Posts the mirror of an entry: same date, currency, rate, and closing
	 * flag, every line's sides swapped, linked to the original, which stays
	 * posted. Refused (409) if the entry was already reversed or any of its
	 * lines is matched on a bank statement, and (422) if no open period
	 * covers its date.
	 */
	public int reverse(int entryId) {
		String date = jdbc.sql("SELECT entry_date::text FROM journal_entries WHERE id = ?").param(entryId)
				.query(String.class).optional().orElseThrow(ServiceException::notFound);
		if (exists("SELECT 1 FROM journal_entries WHERE reverses_entry_id = ?", entryId)) {
			throw ServiceException.conflict("journal entry " + entryId + " is already reversed");
		}
		if (exists("""
				SELECT 1 FROM bank_statement_lines b JOIN journal_lines jl ON jl.id = b.journal_line_id
				WHERE jl.journal_entry_id = ?""", entryId)) {
			throw ServiceException.conflict("journal entry " + entryId + " has lines matched on a bank statement");
		}
		int period = periodFor(date);
		int reversal = jdbc.sql("""
				INSERT INTO journal_entries (entry_date, period_id, currency_code, exchange_rate, memo,
				    reverses_entry_id, status, posted_at, is_closing)
				SELECT entry_date, :period, currency_code, exchange_rate, 'Reversal of journal entry ' || id,
				    id, 'posted', now(), is_closing
				FROM journal_entries WHERE id = :entry
				RETURNING id""").param("period", period).param("entry", entryId).query(Integer.class).single();
		jdbc.sql("""
				INSERT INTO journal_lines (journal_entry_id, line_no, account_id, debit, credit, memo, base_debit, base_credit)
				SELECT ?, line_no, account_id, credit, debit, memo, base_credit, base_debit
				FROM journal_lines WHERE journal_entry_id = ?""").params(reversal, entryId).update();
		return reversal;
	}

	private boolean exists(String select, int id) {
		return jdbc.sql("SELECT EXISTS (" + select + ")").param(id).query(Boolean.class).single();
	}
}
