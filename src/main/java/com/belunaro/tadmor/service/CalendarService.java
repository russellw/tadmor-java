package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;
import static com.belunaro.tadmor.service.ServiceException.found;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Fiscal years and accounting periods (spec/api.md §5.7, spec/domain.md
 * §9.1). Year-end close and reopen post journal entries and belong with
 * posting.
 *
 * <p>The schema enforces the calendar's rules: unique year names and period
 * names within a year (409), end not before start, periods that overlap no
 * other period in any year (an exclusion constraint), and no open period in
 * a closed year (a trigger), all 422. Dates arrive as strings and are cast
 * in SQL, so a malformed or impossible date is a 422 too.
 */
@Service
public class CalendarService {

	private final JdbcClient jdbc;

	public CalendarService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- fiscal years ----

	public record FiscalYear(int id, String name, String startDate, String endDate, String status) {
	}

	/** Status is not part of the body: open and closed belong to close and reopen. */
	public record FiscalYearInput(String name, String startDate, String endDate) {
		public FiscalYearInput {
			name = trim(name);
			startDate = trim(startDate);
			endDate = trim(endDate);
		}
	}

	private static final String FISCAL_YEAR = """
			SELECT id, name, start_date::text AS start_date, end_date::text AS end_date, status FROM fiscal_years""";

	public List<FiscalYear> fiscalYears() {
		return jdbc.sql(FISCAL_YEAR + " ORDER BY start_date, id").query(FiscalYear.class).list();
	}

	public FiscalYear fiscalYear(int id) {
		return jdbc.sql(FISCAL_YEAR + " WHERE id = ?").param(id).query(FiscalYear.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	/** Creates the year, open. */
	public int createFiscalYear(FiscalYearInput in) {
		requireFiscalYear(in);
		return jdbc.sql("""
				INSERT INTO fiscal_years (name, start_date, end_date)
				VALUES (:name, CAST(:startDate AS date), CAST(:endDate AS date))
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	public void updateFiscalYear(int id, FiscalYearInput in) {
		requireFiscalYear(in);
		found(jdbc.sql("""
				UPDATE fiscal_years SET name = :name, start_date = CAST(:startDate AS date),
				    end_date = CAST(:endDate AS date)
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	private static void requireFiscalYear(FiscalYearInput in) {
		require(in.name(), "name");
		require(in.startDate(), "start_date");
		require(in.endDate(), "end_date");
	}

	// ---- accounting periods ----

	public record AccountingPeriod(int id, int fiscalYearId, String name, String startDate, String endDate,
			String status) {
	}

	/**
	 * Status is ignored on create, where periods start open; on update it
	 * defaults to open, so editing a period is also how it is closed or
	 * reopened.
	 */
	public record AccountingPeriodInput(Integer fiscalYearId, String name, String startDate, String endDate,
			String status) {
		public AccountingPeriodInput {
			name = trim(name);
			startDate = trim(startDate);
			endDate = trim(endDate);
			status = status == null || status.isBlank() ? "open" : status.strip();
		}
	}

	private static final String PERIOD = """
			SELECT id, fiscal_year_id, name, start_date::text AS start_date, end_date::text AS end_date, status
			FROM accounting_periods""";

	public List<AccountingPeriod> periods() {
		return jdbc.sql(PERIOD + " ORDER BY start_date, id").query(AccountingPeriod.class).list();
	}

	public AccountingPeriod period(int id) {
		return jdbc.sql(PERIOD + " WHERE id = ?").param(id).query(AccountingPeriod.class).optional()
				.orElseThrow(ServiceException::notFound);
	}

	public int createPeriod(AccountingPeriodInput in) {
		requirePeriod(in);
		return jdbc.sql("""
				INSERT INTO accounting_periods (fiscal_year_id, name, start_date, end_date)
				VALUES (:fiscalYearId, :name, CAST(:startDate AS date), CAST(:endDate AS date))
				RETURNING id""").paramSource(in).query(Integer.class).single();
	}

	public void updatePeriod(int id, AccountingPeriodInput in) {
		requirePeriod(in);
		found(jdbc.sql("""
				UPDATE accounting_periods SET fiscal_year_id = :fiscalYearId, name = :name,
				    start_date = CAST(:startDate AS date), end_date = CAST(:endDate AS date), status = :status
				WHERE id = :id""").paramSource(Params.of(in, "id", id)).update());
	}

	private static void requirePeriod(AccountingPeriodInput in) {
		if (in.fiscalYearId() == null || in.fiscalYearId() <= 0) {
			throw ServiceException.badRequest("fiscal_year_id is required");
		}
		require(in.name(), "name");
		require(in.startDate(), "start_date");
		require(in.endDate(), "end_date");
	}
}
