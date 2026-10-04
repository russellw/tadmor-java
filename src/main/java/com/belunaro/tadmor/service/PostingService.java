package com.belunaro.tadmor.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posting and unposting invoices, bills, and credit notes (spec/domain.md
 * §4), each in one
 * transaction that either fully succeeds or changes nothing.
 *
 * <p>A document posts one entry, dated on the document date, in its
 * currency, with its number as the reference (domain §4.3). Lines are
 * summed per account, and tax per tax account; an account netting to zero
 * gets no line, and one netting negative (a discount on its own revenue
 * account, say) posts on the opposite side. Each detail line's base amount
 * is round(amount × rate, 4); the control line carries the document total,
 * and its base amount is the net of the detail lines' base amounts, so the
 * entry balances in base exactly (domain §7.2).
 */
@Service
public class PostingService {

	private final JdbcClient jdbc;
	private final Journal journal;

	public PostingService(JdbcClient jdbc, Journal journal) {
		this.jdbc = jdbc;
		this.journal = journal;
	}

	private record Draft(String status, String currencyCode, String date, String number, Integer controlAccountId,
			boolean hasTotal) {
	}

	/**
	 * Posts the document and returns its entry's id, after the checks of
	 * domain §4.2 in order: it exists (404), is a draft (409), has a positive
	 * total, a control account, an account for every line with a subtotal
	 * and a tax account for every line with tax, an open period, and an
	 * exchange rate (all 422).
	 */
	@Transactional
	public int post(DocumentKind k, int id) {
		Draft d = jdbc.sql("SELECT d.status, d.currency_code, d." + k.date + "::text AS date, d." + k.number
				+ " AS number, p." + k.control + " AS control_account_id, d.total > 0 AS has_total FROM " + k.table
				+ " d JOIN " + k.partyTable + " p ON p.id = d." + k.party + " WHERE d.id = ? FOR UPDATE OF d")
				.param(id).query(Draft.class).optional().orElseThrow(ServiceException::notFound);
		if (!d.status().equals("draft")) {
			throw ServiceException.conflict(k.label + " " + id + " is " + d.status() + ", not a draft");
		}
		if (!d.hasTotal()) {
			throw ServiceException.unprocessable(k.label + " " + id + " has nothing to post: its total is not positive");
		}
		if (d.controlAccountId() == null) {
			throw ServiceException.unprocessable("the " + (k.control.startsWith("ar") ? "customer has no A/R"
					: "supplier has no A/P") + " account");
		}
		if (count("SELECT count(*) FROM " + k.linesTable + " l LEFT JOIN products p ON p.id = l.product_id WHERE l."
				+ k.lineDocument + " = ? AND l.line_subtotal <> 0 AND COALESCE(l." + k.account + ", p." + k.productAccount
				+ ") IS NULL", id) > 0) {
			throw ServiceException.unprocessable("a line has no " + k.account.replace("_id", "")
					+ ", and its product supplies none");
		}
		if (count("SELECT count(*) FROM " + k.linesTable + " l LEFT JOIN tax_codes tc ON tc.code = l.tax_code WHERE l."
				+ k.lineDocument + " = ? AND l.tax_amount <> 0 AND tc.tax_account_id IS NULL", id) > 0) {
			throw ServiceException.unprocessable("a taxed line has no tax code with a tax account");
		}

		int period = journal.periodFor(d.date());
		int entry = journal.createEntry(d.date(), period, d.currencyCode(), k.label + " " + d.number(), d.number());

		// Detail lines; sign is +1 where they post on the credit side.
		int sign = k.detailSide == DocumentKind.Side.CREDIT ? 1 : -1;
		jdbc.sql("""
				WITH detail AS (
				    SELECT 0 AS ord, COALESCE(l.%1$s, p.%2$s) AS account_id, sum(l.line_subtotal) AS amount, 'Detail' AS memo
				    FROM %3$s l LEFT JOIN products p ON p.id = l.product_id
				    WHERE l.%4$s = :document GROUP BY 2 HAVING sum(l.line_subtotal) <> 0
				    UNION ALL
				    SELECT 1, tc.tax_account_id, sum(l.tax_amount), 'Tax'
				    FROM %3$s l JOIN tax_codes tc ON tc.code = l.tax_code
				    WHERE l.%4$s = :document AND l.tax_amount <> 0 GROUP BY 2 HAVING sum(l.tax_amount) <> 0
				), signed AS (
				    SELECT ord, account_id, memo, :sign * amount AS credit_amount,
				        :sign * round(amount * (SELECT exchange_rate FROM journal_entries WHERE id = :entry), 4) AS credit_base
				    FROM detail
				)
				INSERT INTO journal_lines (journal_entry_id, line_no, account_id, debit, credit, memo, base_debit, base_credit)
				SELECT :entry, row_number() OVER (ORDER BY ord, account_id), account_id,
				    greatest(-credit_amount, 0), greatest(credit_amount, 0), memo,
				    greatest(-credit_base, 0), greatest(credit_base, 0)
				FROM signed""".formatted(k.account, k.productAccount, k.linesTable, k.lineDocument))
				.param("document", id).param("sign", sign).param("entry", entry).update();

		// The control line: the document total, on the other side, at the net of the detail base amounts.
		jdbc.sql("""
				INSERT INTO journal_lines (journal_entry_id, line_no, account_id, debit, credit, memo, base_debit, base_credit)
				SELECT :entry, (SELECT count(*) + 1 FROM journal_lines WHERE journal_entry_id = :entry), p.%1$s,
				    CASE WHEN :sign = 1 THEN d.total ELSE 0 END, CASE WHEN :sign = 1 THEN 0 ELSE d.total END, 'Control',
				    CASE WHEN :sign = 1 THEN b.net ELSE 0 END, CASE WHEN :sign = 1 THEN 0 ELSE -b.net END
				FROM %2$s d JOIN %3$s p ON p.id = d.%4$s,
				    (SELECT COALESCE(sum(base_credit - base_debit), 0) AS net FROM journal_lines
				     WHERE journal_entry_id = :entry) b
				WHERE d.id = :document""".formatted(k.control, k.table, k.partyTable, k.party))
				.param("document", id).param("sign", sign).param("entry", entry).update();

		jdbc.sql("UPDATE " + k.table + " SET status = 'posted', journal_entry_id = ?, period_id = ? WHERE id = ?")
				.params(entry, period, id).update();
		return entry;
	}

	/**
	 * Unposts the document (administrators only) and returns the reversal's
	 * id: a mirror entry, the original left posted, and the document back in
	 * draft with no entry (domain §4.4). Refused (409) if it is not posted,
	 * anything is applied to it, its entry was already reversed or is matched
	 * on a bank statement, and (422) if no open period covers its date.
	 */
	@Transactional
	public int unpost(DocumentKind k, int id) {
		record Posted(String status, Integer journalEntryId) {
		}
		Posted p = jdbc.sql("SELECT status, journal_entry_id FROM " + k.table + " WHERE id = ? FOR UPDATE").param(id)
				.query(Posted.class).optional().orElseThrow(ServiceException::notFound);
		if (!p.status().equals("posted") || p.journalEntryId() == null) {
			throw ServiceException.conflict(k.label + " " + id + " is " + p.status() + ", not posted");
		}
		if (jdbc.sql("SELECT EXISTS (" + k.applications + ")").param("id", id).query(Boolean.class).single()) {
			throw ServiceException.conflict(k.label + " " + id + " has applications that must be removed first");
		}
		int reversal = journal.reverse(p.journalEntryId());
		jdbc.sql("UPDATE " + k.table + " SET status = 'draft', journal_entry_id = NULL, period_id = NULL WHERE id = ?")
				.param(id).update();
		return reversal;
	}

	private int count(String sql, int id) {
		return jdbc.sql(sql).param(id).query(Integer.class).single();
	}
}
