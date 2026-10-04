package com.belunaro.tadmor.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applications: allocating posted payments and credit notes to posted
 * invoices and bills of the same party and currency (spec/domain.md §5),
 * and the realized exchange differences they can produce (§7.3). The
 * schema refuses any application across parties or currencies and any
 * over-application, of the settler or of the document.
 */
@Service
public class SettlementService {

	private final JdbcClient jdbc;
	private final Journal journal;

	public SettlementService(JdbcClient jdbc, Journal journal) {
		this.jdbc = jdbc;
		this.journal = journal;
	}

	/** An application as apply reports it. */
	public record Applied(int documentId, String amountApplied) {
	}

	/** An application as the settler's list shows it. */
	public record Application(int documentId, String documentNumber, String amountApplied) {
	}

	/**
	 * Auto-apply (domain §5.1): spreads the settler's unapplied remainder over
	 * the party's open documents in the same currency, oldest first by date
	 * then id, each receiving min(its available amount, what remains). Returns
	 * the applications created, possibly none. The settler must be posted
	 * (409). A realized exchange difference with no FX account configured is
	 * a 422, and then nothing is applied.
	 */
	@Transactional
	public List<Applied> apply(SettlerKind k, int id) {
		String status = jdbc.sql("SELECT status FROM " + k.table + " WHERE id = ? FOR UPDATE").param(id)
				.query(String.class).optional().orElseThrow(ServiceException::notFound);
		if (!status.equals("posted")) {
			throw ServiceException.conflict(k.label + " " + id + " is " + status + "; only a posted one can be applied");
		}
		DocumentKind t = k.target;
		record Created(int applicationId, int documentId, String amountApplied) {
		}
		List<Created> created = jdbc.sql("""
				WITH s AS (
				    SELECT s.%7$s AS party, s.currency_code,
				        s.%2$s - COALESCE((SELECT sum(amount_applied) FROM %3$s WHERE %4$s = s.id), 0) AS remaining
				    FROM %1$s s WHERE s.id = :id
				), candidates AS (
				    SELECT d.id, d.%8$s AS date, d.total - %9$s(d.id) AS available
				    FROM %6$s d JOIN s ON d.%7$s = s.party AND d.currency_code = s.currency_code
				    WHERE d.status = 'posted'
				), ranked AS (
				    SELECT id, date, available,
				        COALESCE(sum(available) OVER (ORDER BY date, id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
				            AS prior
				    FROM candidates WHERE available > 0
				), allocated AS (
				    SELECT id, date, LEAST(available, GREATEST((SELECT remaining FROM s) - prior, 0)) AS amount FROM ranked
				)
				INSERT INTO %3$s (%4$s, %5$s, amount_applied)
				SELECT :id, id, amount FROM allocated WHERE amount > 0 ORDER BY date, id
				RETURNING id AS application_id, %5$s AS document_id, amount_applied::text AS amount_applied"""
				.formatted(k.table, k.amount, k.applications, k.settlerColumn, k.targetColumn(), t.table, t.party, t.date,
						k.settled))
				.param("id", id).query(Created.class).list();
		postExchangeDifferences(k, id);
		return created.stream().sorted(Comparator.comparingInt(Created::applicationId))
				.map(c -> new Applied(c.documentId(), c.amountApplied())).toList();
	}

	/**
	 * For each of the settler's applications whose documents were posted at
	 * different rates, posts an FX entry in base currency on the settler's
	 * date, between the party's control account and the FX gain/loss account,
	 * for diff = round(applied × settler rate, 4) − round(applied × document
	 * rate, 4), and links it to the application (domain §7.3).
	 */
	private void postExchangeDifferences(SettlerKind k, int id) {
		DocumentKind t = k.target;
		record Difference(int applicationId, String number, int controlAccountId, String date, String diff) {
		}
		List<Difference> differences = jdbc.sql("""
				SELECT a.id AS application_id, d.%6$s AS number, p.%7$s AS control_account_id, s.%8$s::text AS date,
				    (round(a.amount_applied * es.exchange_rate, 4) - round(a.amount_applied * ed.exchange_rate, 4))::text AS diff
				FROM %2$s a
				JOIN %1$s s ON s.id = a.%3$s
				JOIN %5$s d ON d.id = a.%4$s
				JOIN %9$s p ON p.id = d.%10$s
				JOIN journal_entries es ON es.id = s.journal_entry_id
				JOIN journal_entries ed ON ed.id = d.journal_entry_id
				WHERE a.%3$s = :id AND a.fx_journal_entry_id IS NULL
				  AND round(a.amount_applied * es.exchange_rate, 4) <> round(a.amount_applied * ed.exchange_rate, 4)
				ORDER BY a.id""".formatted(k.table, k.applications, k.settlerColumn, k.targetColumn(), t.table, t.number,
				t.control, k.date, t.partyTable, t.party))
				.param("id", id).query(Difference.class).list();
		if (differences.isEmpty()) {
			return;
		}
		record Settings(String baseCurrency, Integer fxGainLossAccountId) {
		}
		Settings settings = jdbc.sql("SELECT base_currency, fx_gain_loss_account_id FROM gl_settings")
				.query(Settings.class).single();
		if (settings.fxGainLossAccountId() == null) {
			throw ServiceException.unprocessable(
					"this application realizes an exchange difference, and no FX gain/loss account is configured");
		}
		// A positive diff debits A/R (a gain) or credits A/P (a loss).
		int sign = k.receivable ? 1 : -1;
		for (Difference d : differences) {
			int period = journal.periodFor(d.date());
			int entry = journal.createEntry(d.date(), period, settings.baseCurrency(),
					"Exchange difference on settlement of " + t.label.toLowerCase() + " " + d.number(), d.number());
			jdbc.sql("""
					INSERT INTO journal_lines (journal_entry_id, line_no, account_id, debit, credit, memo, base_debit, base_credit)
					SELECT :entry, 1, :control, greatest(v, 0), greatest(-v, 0), 'Settlement revaluation', greatest(v, 0), greatest(-v, 0)
					FROM (SELECT CAST(:diff AS numeric) * :sign AS v) x
					UNION ALL
					SELECT :entry, 2, :fx, greatest(-v, 0), greatest(v, 0), 'Exchange gain (loss)', greatest(-v, 0), greatest(v, 0)
					FROM (SELECT CAST(:diff AS numeric) * :sign AS v) x""")
					.param("entry", entry).param("control", d.controlAccountId()).param("fx", settings.fxGainLossAccountId())
					.param("diff", d.diff()).param("sign", sign).update();
			jdbc.sql("UPDATE " + k.applications + " SET fx_journal_entry_id = ? WHERE id = ?")
					.params(entry, d.applicationId()).update();
		}
	}

	/** The settler's applications, in creation order. */
	public List<Application> applications(SettlerKind k, int id) {
		if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + k.table + " WHERE id = ?)").param(id).query(Boolean.class).single()) {
			throw ServiceException.notFound();
		}
		DocumentKind t = k.target;
		return jdbc.sql("SELECT a." + k.targetColumn() + " AS document_id, d." + t.number + " AS document_number, "
				+ "a.amount_applied::text AS amount_applied FROM " + k.applications + " a JOIN " + t.table
				+ " d ON d.id = a." + k.targetColumn() + " WHERE a." + k.settlerColumn + " = ? ORDER BY a.id")
				.param(id).query(Application.class).list();
	}

	/**
	 * Removes the settler's applications, reversing the FX entries they
	 * created, as unposting a payment does (domain §4.4). Runs inside the
	 * caller's transaction.
	 */
	void unwind(SettlerKind k, int id) {
		for (int entry : jdbc.sql("SELECT fx_journal_entry_id FROM " + k.applications + " WHERE " + k.settlerColumn
				+ " = ? AND fx_journal_entry_id IS NOT NULL ORDER BY id").param(id).query(Integer.class).list()) {
			journal.reverse(entry);
		}
		jdbc.sql("DELETE FROM " + k.applications + " WHERE " + k.settlerColumn + " = ?").param(id).update();
	}
}
