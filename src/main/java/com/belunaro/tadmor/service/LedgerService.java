package com.belunaro.tadmor.service;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

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

	// ---- statements (spec/domain.md §10) ----

	/** An account's activity in its natural sign. */
	public record ActivityRow(int accountId, String code, String name, String accountType, String amount) {
	}

	/** Posted lines joined to their entry and account, in base amounts. */
	private static final String LINES = """
			FROM journal_lines jl
			JOIN journal_entries je ON je.id = jl.journal_entry_id
			JOIN accounts a ON a.id = jl.account_id
			WHERE je.status = 'posted'""";

	private static final String IN_RANGE = """
			  AND (CAST(:from AS date) IS NULL OR je.entry_date >= CAST(:from AS date))
			  AND (CAST(:to AS date) IS NULL OR je.entry_date <= CAST(:to AS date))""";

	/**
	 * Revenue and expense accounts with lines in the range, excluding closing
	 * entries: revenue as Σ(credit − debit), expense as Σ(debit − credit).
	 */
	public List<ActivityRow> profitAndLoss(LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT a.id AS account_id, a.code, a.name, a.account_type,
				    sum(CASE WHEN a.account_type = 'revenue' THEN jl.base_credit - jl.base_debit
				             ELSE jl.base_debit - jl.base_credit END)::numeric(19,4)::text AS amount
				""" + LINES + """

				  AND NOT je.is_closing AND a.account_type IN ('revenue', 'expense')
				""" + IN_RANGE + """

				GROUP BY a.id, a.code, a.name, a.account_type
				ORDER BY a.code""").param("from", from).param("to", to).query(ActivityRow.class).list();
	}

	public record BalanceSheet(List<ActivityRow> rows, String currentEarnings) {
	}

	/**
	 * Asset, liability, and equity accounts with lines on or before the date
	 * (assets debit-positive, the others credit-positive), and the earnings
	 * not yet closed into equity: Σ(credit − debit) over revenue and expense
	 * lines, closing entries included, so that assets = liabilities + equity
	 * + current earnings.
	 */
	public BalanceSheet balanceSheet(LocalDate asOf) {
		List<ActivityRow> rows = jdbc.sql("""
				SELECT a.id AS account_id, a.code, a.name, a.account_type,
				    sum(CASE WHEN a.account_type = 'asset' THEN jl.base_debit - jl.base_credit
				             ELSE jl.base_credit - jl.base_debit END)::numeric(19,4)::text AS amount
				""" + LINES + """

				  AND a.account_type IN ('asset', 'liability', 'equity')
				  AND (CAST(:to AS date) IS NULL OR je.entry_date <= CAST(:to AS date))
				GROUP BY a.id, a.code, a.name, a.account_type
				ORDER BY a.code""").param("to", asOf).query(ActivityRow.class).list();
		String earnings = jdbc.sql("SELECT COALESCE(sum(jl.base_credit - jl.base_debit), 0)::numeric(19,4)::text " + LINES + """

				  AND a.account_type IN ('revenue', 'expense')
				  AND (CAST(:to AS date) IS NULL OR je.entry_date <= CAST(:to AS date))""")
				.param("to", asOf).query(String.class).single();
		return new BalanceSheet(rows, earnings);
	}

	public record CashFlowRow(int accountId, String code, String name, String activity, String amount) {
	}

	public record CashFlow(String netIncome, List<CashFlowRow> rows, String netCashFlow, String openingCash,
			String closingCash) {
	}

	/**
	 * The indirect cash-flow statement: net income, then each non-cash
	 * balance-sheet account's Σ(credit − debit) in the range, labelled with
	 * its activity (closing entries excluded from both), and the cash
	 * accounts' opening balance, movement, and closing balance, so that net
	 * income + Σ rows = net cash flow, and opening + net = closing.
	 */
	public CashFlow cashFlow(LocalDate from, LocalDate to) {
		String netIncome = jdbc.sql("SELECT COALESCE(sum(jl.base_credit - jl.base_debit), 0)::numeric(19,4)::text " + LINES
				+ "\n  AND NOT je.is_closing AND a.account_type IN ('revenue', 'expense')\n" + IN_RANGE)
				.param("from", from).param("to", to).query(String.class).single();
		List<CashFlowRow> rows = jdbc.sql("""
				SELECT a.id AS account_id, a.code, a.name, a.cash_flow_activity AS activity,
				    sum(jl.base_credit - jl.base_debit)::numeric(19,4)::text AS amount
				""" + LINES + """

				  AND NOT je.is_closing AND NOT a.is_cash AND a.account_type IN ('asset', 'liability', 'equity')
				""" + IN_RANGE + """

				GROUP BY a.id, a.code, a.name, a.cash_flow_activity
				ORDER BY a.code""").param("from", from).param("to", to).query(CashFlowRow.class).list();
		record Cash(String openingCash, String netCashFlow, String closingCash) {
		}
		Cash cash = jdbc.sql("""
				SELECT COALESCE(sum(jl.base_debit - jl.base_credit)
				           FILTER (WHERE CAST(:from AS date) IS NOT NULL AND je.entry_date < CAST(:from AS date)), 0)
				           ::numeric(19,4)::text AS opening_cash,
				       COALESCE(sum(jl.base_debit - jl.base_credit)
				           FILTER (WHERE (CAST(:from AS date) IS NULL OR je.entry_date >= CAST(:from AS date))
				                     AND (CAST(:to AS date) IS NULL OR je.entry_date <= CAST(:to AS date))), 0)
				           ::numeric(19,4)::text AS net_cash_flow,
				       COALESCE(sum(jl.base_debit - jl.base_credit)
				           FILTER (WHERE CAST(:to AS date) IS NULL OR je.entry_date <= CAST(:to AS date)), 0)
				           ::numeric(19,4)::text AS closing_cash
				""" + LINES + "\n  AND a.is_cash").param("from", from).param("to", to).query(Cash.class).single();
		return new CashFlow(netIncome, rows, cash.netCashFlow(), cash.openingCash(), cash.closingCash());
	}

	// ---- aging ----

	/** A party's posted documents with a balance, bucketed by due date against today (UTC). */
	public record AgingRow(int partyId, String partyName, String totalOutstanding, String notYetDue,
			// Digits defeat both name conversions: the row mapper looks for columns named
			// days130 and days_over90 (aliased so in the query), and snake case would
			// render those names too.
			@JsonProperty("days_1_30") String days130, @JsonProperty("days_31_60") String days3160,
			@JsonProperty("days_61_90") String days6190, @JsonProperty("days_over_90") String daysOver90) {
	}

	/** Receivables aging, from the schema's ar_aging view. */
	public List<AgingRow> receivablesAging() {
		return aging("ar_aging", "customer_id", "customers");
	}

	/** Payables aging, from the schema's ap_aging view. */
	public List<AgingRow> payablesAging() {
		return aging("ap_aging", "supplier_id", "suppliers");
	}

	private List<AgingRow> aging(String view, String party, String partyTable) {
		return jdbc.sql("""
				SELECT g.%2$s AS party_id, o.name AS party_name,
				    COALESCE(g.total_outstanding, 0)::numeric(19,4)::text AS total_outstanding,
				    COALESCE(g.not_yet_due, 0)::numeric(19,4)::text AS not_yet_due,
				    COALESCE(g.days_1_30, 0)::numeric(19,4)::text AS days130,
				    COALESCE(g.days_31_60, 0)::numeric(19,4)::text AS days3160,
				    COALESCE(g.days_61_90, 0)::numeric(19,4)::text AS days6190,
				    COALESCE(g.days_over_90, 0)::numeric(19,4)::text AS days_over90
				FROM %1$s g JOIN %3$s p ON p.id = g.%2$s JOIN organizations o ON o.id = p.organization_id
				ORDER BY g.%2$s""".formatted(view, party, partyTable)).query(AgingRow.class).list();
	}
}
