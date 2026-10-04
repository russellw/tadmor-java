package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bank reconciliation (spec/api.md §5.13, spec/domain.md §8): statements of a
 * cash account, their lines, and matching each line to the posted journal
 * line it corresponds to. Amounts are signed from the books' side, a deposit
 * positive.
 *
 * <p>The schema enforces most of it: a statement's account must be a
 * postable, active cash account, and cannot change while lines are matched;
 * a reconciled statement and its lines are frozen; a matched journal line
 * must be posted, on the statement's account, and of the same signed amount
 * (all 422); and a journal line backs at most one statement line (a unique
 * index, 409).
 */
@Service
public class BankService {

	private static final Pattern AMOUNT = Pattern.compile("-?(\\d+(\\.\\d*)?|\\.\\d+)");

	private final JdbcClient jdbc;

	public BankService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- shapes ----

	public record BankStatement(int id, int accountId, String accountCode, String accountName, String statementDate,
			String openingBalance, String closingBalance, String reference, String status, int lineCount,
			int matchedCount, String linesTotal, String difference) {
	}

	public record StatementInput(Integer accountId, String statementDate, String openingBalance, String closingBalance,
			String reference) {
		public StatementInput {
			statementDate = trim(statementDate);
			openingBalance = trim(openingBalance);
			closingBalance = trim(closingBalance);
		}
	}

	/** The journal_* and entry_* fields are null while the line is unmatched. */
	public record StatementLine(int id, int lineNo, String txnDate, String description, String reference, String amount,
			Integer journalLineId, Integer journalEntryId, String entryDate, String entryMemo) {
	}

	public record LineInput(String txnDate, String description, String reference, String amount) {
		public LineInput {
			txnDate = trim(txnDate);
			description = trim(description);
			amount = trim(amount);
		}
	}

	public record MatchCandidate(int journalLineId, int journalEntryId, String entryDate, String reference, String memo,
			String amount) {
	}

	// ---- statements ----

	private static final String STATEMENT = """
			SELECT s.id, s.account_id, a.code AS account_code, a.name AS account_name,
			    s.statement_date::text AS statement_date, s.opening_balance::text AS opening_balance,
			    s.closing_balance::text AS closing_balance, s.reference, s.status,
			    count(l.id) AS line_count, count(l.journal_line_id) AS matched_count,
			    COALESCE(sum(l.amount), 0)::numeric(19,4)::text AS lines_total,
			    (s.opening_balance + COALESCE(sum(l.amount), 0) - s.closing_balance)::numeric(19,4)::text AS difference
			FROM bank_statements s
			JOIN accounts a ON a.id = s.account_id
			LEFT JOIN bank_statement_lines l ON l.statement_id = s.id""";

	private static final String GROUP = " GROUP BY s.id, a.code, a.name";

	/** Newest first, then by id descending. */
	public List<BankStatement> statements() {
		return jdbc.sql(STATEMENT + GROUP + " ORDER BY s.statement_date DESC, s.id DESC").query(BankStatement.class).list();
	}

	public BankStatement statement(int id) {
		return jdbc.sql(STATEMENT + " WHERE s.id = ?" + GROUP).param(id).query(BankStatement.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createStatement(StatementInput in) {
		validate(in);
		return jdbc.sql("""
				INSERT INTO bank_statements (account_id, statement_date, opening_balance, closing_balance, reference)
				VALUES (:accountId, CAST(:statementDate AS date), CAST(:openingBalance AS numeric),
				    CAST(:closingBalance AS numeric), :reference)
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	@Transactional
	public void updateStatement(int id, StatementInput in) {
		validate(in);
		requireOpen(id);
		jdbc.sql("""
				UPDATE bank_statements SET account_id = :accountId, statement_date = CAST(:statementDate AS date),
				    opening_balance = CAST(:openingBalance AS numeric), closing_balance = CAST(:closingBalance AS numeric),
				    reference = :reference
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update();
	}

	@Transactional
	public void deleteStatement(int id) {
		requireOpen(id);
		jdbc.sql("DELETE FROM bank_statements WHERE id = ?").param(id).update();
	}

	/** Every line matched and opening + Σ lines = closing (422 otherwise). */
	@Transactional
	public void reconcile(int id) {
		requireOpen(id);
		record Check(int unmatched, boolean balances) {
		}
		Check c = jdbc.sql("""
				SELECT count(*) FILTER (WHERE l.id IS NOT NULL AND l.journal_line_id IS NULL) AS unmatched,
				    s.opening_balance + COALESCE(sum(l.amount), 0) = s.closing_balance AS balances
				FROM bank_statements s LEFT JOIN bank_statement_lines l ON l.statement_id = s.id
				WHERE s.id = ? GROUP BY s.id""").param(id).query(Check.class).single();
		if (c.unmatched() > 0) {
			throw ServiceException.unprocessable("bank statement " + id + " has " + c.unmatched() + " unmatched lines");
		}
		if (!c.balances()) {
			throw ServiceException.unprocessable("bank statement " + id + " does not balance: opening + lines <> closing");
		}
		jdbc.sql("UPDATE bank_statements SET status = 'reconciled', reconciled_at = now() WHERE id = ?").param(id).update();
	}

	/** Administrators only. */
	@Transactional
	public void reopen(int id) {
		String status = status(id);
		if (!status.equals("reconciled")) {
			throw ServiceException.conflict("bank statement " + id + " is " + status + ", not reconciled");
		}
		jdbc.sql("UPDATE bank_statements SET status = 'open', reconciled_at = NULL WHERE id = ?").param(id).update();
	}

	// ---- lines ----

	public List<StatementLine> lines(int id) {
		status(id);
		return jdbc.sql("""
				SELECT l.id, l.line_no, l.txn_date::text AS txn_date, l.description, l.reference, l.amount::text AS amount,
				    l.journal_line_id, je.id AS journal_entry_id, je.entry_date::text AS entry_date,
				    COALESCE(jl.memo, je.memo) AS entry_memo
				FROM bank_statement_lines l
				LEFT JOIN journal_lines jl ON jl.id = l.journal_line_id
				LEFT JOIN journal_entries je ON je.id = jl.journal_entry_id
				WHERE l.statement_id = ? ORDER BY l.line_no""").param(id).query(StatementLine.class).list();
	}

	/** Appends a line after any existing ones. */
	@Transactional
	public int addLine(int statementId, LineInput in) {
		require(in.txnDate(), "txn_date");
		require(in.description(), "description");
		require(in.amount(), "amount");
		requireOpen(statementId);
		return insertLine(statementId, in);
	}

	private int insertLine(int statementId, LineInput in) {
		return jdbc.sql("""
				INSERT INTO bank_statement_lines (statement_id, line_no, txn_date, description, reference, amount)
				VALUES (:statement, COALESCE((SELECT max(line_no) FROM bank_statement_lines WHERE statement_id = :statement), 0) + 1,
				    CAST(:txnDate AS date), :description, :reference, CAST(:amount AS numeric))
				RETURNING id""").paramSource(Params.of(in, "statement", statementId)).query(Integer.class).single();
	}

	/**
	 * Imports date,description,amount[,reference] CSV (domain §8.2), all or
	 * nothing: a first record whose date is not YYYY-MM-DD is a header and
	 * skipped, blank records are ignored, and any bad record, or no data at
	 * all, is a 422.
	 */
	@Transactional
	public int importCsv(int statementId, String csv) {
		if (csv == null || csv.isBlank()) {
			throw ServiceException.badRequest("csv is required");
		}
		requireOpen(statementId);
		List<LineInput> lines = parse(csv);
		lines.forEach(l -> insertLine(statementId, l));
		return lines.size();
	}

	private static List<LineInput> parse(String csv) {
		List<List<String>> records;
		try {
			records = Csv.parse(csv);
		} catch (Csv.MalformedCsvException e) {
			throw ServiceException.unprocessable("invalid CSV: " + e.getMessage());
		}
		List<LineInput> out = new ArrayList<>();
		for (int i = 0; i < records.size(); i++) {
			List<String> rec = records.get(i).stream().map(String::strip).toList();
			String where = "record " + (i + 1) + ": ";
			if (rec.size() == 1 && rec.get(0).isEmpty()) {
				continue;
			}
			if (rec.size() != 3 && rec.size() != 4) {
				throw ServiceException.unprocessable(where + "has " + rec.size()
						+ " fields; want date,description,amount[,reference]");
			}
			if (!isDate(rec.get(0))) {
				if (i == 0) {
					continue; // a header
				}
				throw ServiceException.unprocessable(where + rec.get(0) + " is not a YYYY-MM-DD date");
			}
			if (rec.get(1).isEmpty()) {
				throw ServiceException.unprocessable(where + "the description is empty");
			}
			if (!AMOUNT.matcher(rec.get(2)).matches()) {
				throw ServiceException.unprocessable(where + rec.get(2) + " is not a decimal amount");
			}
			if (rec.get(2).replaceAll("[-.0]", "").isEmpty()) {
				throw ServiceException.unprocessable(where + "the amount must not be zero");
			}
			out.add(new LineInput(rec.get(0), rec.get(1), rec.size() == 4 && !rec.get(3).isEmpty() ? rec.get(3) : null,
					rec.get(2)));
		}
		if (out.isEmpty()) {
			throw ServiceException.unprocessable("the CSV has no data rows");
		}
		return out;
	}

	private static boolean isDate(String s) {
		try {
			return s.length() == 10 && LocalDate.parse(s) != null;
		} catch (DateTimeParseException e) {
			return false;
		}
	}

	@Transactional
	public void deleteLine(int lineId) {
		lineStatement(lineId);
		jdbc.sql("DELETE FROM bank_statement_lines WHERE id = ?").param(lineId).update();
	}

	// ---- matching (domain §8.3) ----

	/** The account's posted journal lines that no statement line has claimed. */
	public List<MatchCandidate> candidates(int id) {
		int account = jdbc.sql("SELECT account_id FROM bank_statements WHERE id = ?").param(id).query(Integer.class)
				.optional().orElseThrow(ServiceException::notFound);
		return jdbc.sql("""
				SELECT jl.id AS journal_line_id, je.id AS journal_entry_id, je.entry_date::text AS entry_date, je.reference,
				    COALESCE(jl.memo, je.memo) AS memo, (jl.debit - jl.credit)::numeric(19,4)::text AS amount
				FROM journal_lines jl JOIN journal_entries je ON je.id = jl.journal_entry_id
				WHERE je.status = 'posted' AND jl.account_id = ?
				  AND NOT EXISTS (SELECT 1 FROM bank_statement_lines b WHERE b.journal_line_id = jl.id)
				ORDER BY je.entry_date, je.id, jl.line_no""").param(account).query(MatchCandidate.class).list();
	}

	/**
	 * Matches a statement line to a journal line. Refused (409) if the
	 * statement is not open or the line is already matched; the schema
	 * refuses the rest.
	 */
	@Transactional
	public void match(int lineId, Integer journalLineId) {
		if (journalLineId == null || journalLineId <= 0) {
			throw ServiceException.badRequest("journal_line_id is required");
		}
		lineStatement(lineId);
		if (jdbc.sql("UPDATE bank_statement_lines SET journal_line_id = ? WHERE id = ? AND journal_line_id IS NULL")
				.params(journalLineId, lineId).update() == 0) {
			throw ServiceException.conflict("statement line " + lineId + " is already matched");
		}
	}

	/** A no-op if the line is unmatched. */
	@Transactional
	public void unmatch(int lineId) {
		lineStatement(lineId);
		jdbc.sql("UPDATE bank_statement_lines SET journal_line_id = NULL WHERE id = ?").param(lineId).update();
	}

	/**
	 * Visits the unmatched lines in line order; each takes the unclaimed
	 * candidate of equal amount with the nearest entry date (ties to the
	 * lowest journal-line id), if any. Returns how many were matched.
	 */
	@Transactional
	public int autoMatch(int id) {
		requireOpen(id);
		int matched = 0;
		for (int line : jdbc.sql("SELECT id FROM bank_statement_lines WHERE statement_id = ? AND journal_line_id IS NULL "
				+ "ORDER BY line_no").param(id).query(Integer.class).list()) {
			matched += jdbc.sql("""
					WITH best AS (
					    SELECT jl.id
					    FROM bank_statement_lines l
					    JOIN bank_statements s ON s.id = l.statement_id
					    JOIN journal_lines jl ON jl.account_id = s.account_id AND jl.debit - jl.credit = l.amount
					    JOIN journal_entries je ON je.id = jl.journal_entry_id AND je.status = 'posted'
					    WHERE l.id = :line
					      AND NOT EXISTS (SELECT 1 FROM bank_statement_lines used WHERE used.journal_line_id = jl.id)
					    ORDER BY abs(je.entry_date - l.txn_date), jl.id
					    LIMIT 1
					)
					UPDATE bank_statement_lines SET journal_line_id = (SELECT id FROM best)
					WHERE id = :line AND EXISTS (SELECT 1 FROM best)""").param("line", line).update();
		}
		return matched;
	}

	// ---- status ----

	private String status(int id) {
		return jdbc.sql("SELECT status FROM bank_statements WHERE id = ? FOR UPDATE").param(id).query(String.class)
				.optional().orElseThrow(ServiceException::notFound);
	}

	private void requireOpen(int id) {
		String status = status(id);
		if (!status.equals("open")) {
			throw ServiceException.conflict("bank statement " + id + " is " + status + ", not open");
		}
	}

	/** The statement of a line, which must be open; 404 for an unknown line. */
	private void lineStatement(int lineId) {
		int statement = jdbc.sql("SELECT statement_id FROM bank_statement_lines WHERE id = ?").param(lineId)
				.query(Integer.class).optional().orElseThrow(ServiceException::notFound);
		requireOpen(statement);
	}

	/** 400 for a missing field. */
	private static void validate(StatementInput in) {
		if (in.accountId() == null || in.accountId() <= 0) {
			throw ServiceException.badRequest("account_id is required");
		}
		require(in.statementDate(), "statement_date");
		require(in.openingBalance(), "opening_balance");
		require(in.closingBalance(), "closing_balance");
	}
}
