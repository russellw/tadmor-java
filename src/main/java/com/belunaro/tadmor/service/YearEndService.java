package com.belunaro.tadmor.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing and reopening fiscal years (spec/domain.md §9.3), administrators
 * only. Each runs in one transaction.
 */
@Service
public class YearEndService {

	private final JdbcClient jdbc;
	private final Journal journal;

	public YearEndService(JdbcClient jdbc, Journal journal) {
		this.jdbc = jdbc;
		this.journal = journal;
	}

	/** The closing entry (null if nothing was swept) and the rolled-forward year (null if none was created). */
	public record Closed(Integer closingEntryId, Integer nextFiscalYearId) {
	}

	/** The reversal of the closing entry, or null if the close posted none. */
	public record Reopened(Integer reversalEntryId) {
	}

	private record Year(String name, String endDate, String status, Integer closingEntryId) {
	}

	/** Revenue and expense accounts with a non-zero cumulative base balance (debit-positive) up to :end. */
	private static final String BALANCES = """
			SELECT jl.account_id, sum(jl.base_debit - jl.base_credit) AS balance
			FROM journal_lines jl
			JOIN journal_entries je ON je.id = jl.journal_entry_id
			JOIN accounts a ON a.id = jl.account_id
			WHERE je.status = 'posted' AND a.account_type IN ('revenue', 'expense') AND je.entry_date <= CAST(:end AS date)
			GROUP BY jl.account_id
			HAVING sum(jl.base_debit - jl.base_credit) <> 0""";

	/**
	 * Closes an open year. Refused (400) without a retained-earnings account,
	 * (404) for an unknown year, (409) unless the year is open, and (422) if
	 * an earlier year is still open or the account is not a postable, active
	 * equity account. Then:
	 * <ol>
	 * <li>posts a closing entry on the year's end date, in base currency and
	 * flagged as closing, zeroing each revenue and expense balance against
	 * retained earnings (a credit for net income, a debit for a loss),
	 * unless there is nothing to sweep; it lands in the period covering the
	 * end date, which is created if missing and used even if closed;
	 * <li>closes every period of the year, then the year;
	 * <li>rolls forward: if no year covers the next day, creates one starting
	 * then, a year long, named FY plus the calendar year it ends in, unless
	 * that name is taken.
	 * </ol>
	 */
	@Transactional
	public Closed close(int id, Integer retainedEarningsAccountId) {
		if (retainedEarningsAccountId == null || retainedEarningsAccountId <= 0) {
			throw ServiceException.badRequest("retained_earnings_account_id is required");
		}
		Year year = year(id);
		if (!year.status().equals("open")) {
			throw ServiceException.conflict("fiscal year " + year.name() + " is " + year.status() + ", not open");
		}
		if (jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM fiscal_years WHERE id <> :id AND status = 'open'
				    AND start_date < (SELECT start_date FROM fiscal_years WHERE id = :id))""")
				.param("id", id).query(Boolean.class).single()) {
			throw ServiceException.unprocessable("an earlier fiscal year is still open; close it first");
		}
		boolean equity = jdbc.sql("SELECT is_postable AND is_active AND account_type = 'equity' FROM accounts WHERE id = ?")
				.param(retainedEarningsAccountId).query(Boolean.class).optional().orElse(false);
		if (!equity) {
			throw ServiceException.unprocessable("retained_earnings_account_id must be a postable, active equity account");
		}

		Integer closing = null;
		if (jdbc.sql("SELECT count(*) FROM (" + BALANCES + ") b").param("end", year.endDate()).query(Integer.class).single() > 0) {
			closing = postClosingEntry(year, retainedEarningsAccountId);
		}
		jdbc.sql("UPDATE accounting_periods SET status = 'closed' WHERE fiscal_year_id = ? AND status = 'open'").param(id)
				.update();
		jdbc.sql("UPDATE fiscal_years SET status = 'closed', closing_entry_id = ? WHERE id = ?").params(closing, id).update();
		Integer next = jdbc.sql("""
				INSERT INTO fiscal_years (name, start_date, end_date)
				SELECT 'FY' || to_char(next.end_date, 'YYYY'), next.start_date, next.end_date
				FROM (SELECT CAST(:end AS date) + 1 AS start_date,
				          (CAST(:end AS date) + 1 + interval '1 year' - interval '1 day')::date AS end_date) next
				WHERE NOT EXISTS (SELECT 1 FROM fiscal_years WHERE next.start_date BETWEEN start_date AND end_date)
				ON CONFLICT (name) DO NOTHING
				RETURNING id""").param("end", year.endDate()).query(Integer.class).optional().orElse(null);
		return new Closed(closing, next);
	}

	private int postClosingEntry(Year year, int retainedEarnings) {
		record Period(int id, String status) {
		}
		int period = jdbc.sql("SELECT id, status FROM accounting_periods WHERE CAST(? AS date) BETWEEN start_date AND end_date "
				+ "ORDER BY id LIMIT 1").param(year.endDate()).query(Period.class).optional()
				.map(p -> {
					if (p.status().equals("closed")) {
						jdbc.sql("UPDATE accounting_periods SET status = 'open' WHERE id = ?").param(p.id()).update();
					}
					return p.id();
				})
				.orElseGet(() -> journal.periodFor(year.endDate()));
		int entry = jdbc.sql("""
				INSERT INTO journal_entries (entry_date, period_id, currency_code, memo, reference, status, posted_at, is_closing)
				VALUES (CAST(:end AS date), :period, (SELECT base_currency FROM gl_settings), :memo, :name, 'posted', now(), true)
				RETURNING id""")
				.param("end", year.endDate()).param("period", period).param("memo", "Year-end close " + year.name())
				.param("name", year.name()).query(Integer.class).single();
		jdbc.sql("""
				WITH balances AS (%s), lines AS (
				    SELECT 0 AS ord, account_id, greatest(-balance, 0) AS debit, greatest(balance, 0) AS credit,
				        'Year-end close' AS memo
				    FROM balances
				    UNION ALL
				    SELECT 1, :retained, greatest(t.total, 0), greatest(-t.total, 0), 'Net income (loss) for ' || :name
				    FROM (SELECT sum(balance) AS total FROM balances) t WHERE t.total <> 0
				)
				INSERT INTO journal_lines (journal_entry_id, line_no, account_id, debit, credit, memo, base_debit, base_credit)
				SELECT :entry, row_number() OVER (ORDER BY ord, account_id), account_id, debit, credit, memo, debit, credit
				FROM lines""".formatted(BALANCES))
				.param("end", year.endDate()).param("retained", retainedEarnings).param("name", year.name())
				.param("entry", entry).update();
		return entry;
	}

	/**
	 * Reopens a closed year with no later closed year: refused (404) for an
	 * unknown year, (409) unless it is closed, (422) if a later year is
	 * closed. Sets it open, reopens the period holding the closing entry, and
	 * reverses that entry; other periods stay closed until reopened by hand.
	 */
	@Transactional
	public Reopened reopen(int id) {
		Year year = year(id);
		if (!year.status().equals("closed")) {
			throw ServiceException.conflict("fiscal year " + year.name() + " is " + year.status() + ", not closed");
		}
		if (jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM fiscal_years WHERE status = 'closed'
				    AND start_date > (SELECT start_date FROM fiscal_years WHERE id = ?))""")
				.param(id).query(Boolean.class).single()) {
			throw ServiceException.unprocessable("a later fiscal year is closed; reopen it first");
		}
		// The year first: the schema refuses an open period in a closed year.
		jdbc.sql("UPDATE fiscal_years SET status = 'open', closing_entry_id = NULL WHERE id = ?").param(id).update();
		if (year.closingEntryId() == null) {
			return new Reopened(null);
		}
		jdbc.sql("""
				UPDATE accounting_periods SET status = 'open'
				WHERE id = (SELECT period_id FROM journal_entries WHERE id = ?) AND status = 'closed'""")
				.param(year.closingEntryId()).update();
		return new Reopened(journal.reverse(year.closingEntryId()));
	}

	private Year year(int id) {
		return jdbc.sql("SELECT name, end_date::text AS end_date, status, closing_entry_id FROM fiscal_years WHERE id = ? FOR UPDATE")
				.param(id).query(Year.class).optional().orElseThrow(ServiceException::notFound);
	}
}
