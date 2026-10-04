package com.belunaro.tadmor.service;

import static com.belunaro.tadmor.service.Inputs.require;
import static com.belunaro.tadmor.service.Inputs.trim;
import static com.belunaro.tadmor.service.ServiceException.found;

import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The ledger settings and exchange rates (spec/api.md §5.8). The schema
 * freezes the base currency once any journal entry exists (a trigger, 422),
 * and refuses unknown currencies and rates that are not positive (422).
 * Currency codes are upper-cased as they are stored.
 */
@Service
public class SettingsService {

	private final JdbcClient jdbc;

	public SettingsService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- settings (one row) ----

	public record Settings(String baseCurrency, Integer fxGainLossAccountId) {
		public Settings {
			baseCurrency = trim(baseCurrency).toUpperCase(Locale.ROOT);
		}
	}

	public Settings settings() {
		return jdbc.sql("SELECT base_currency, fx_gain_loss_account_id FROM gl_settings").query(Settings.class).single();
	}

	/**
	 * Replaces the settings. The FX gain/loss account, when set, must be
	 * postable and active, checked here so that a bad choice fails now
	 * rather than at the first foreign-currency settlement.
	 */
	public void updateSettings(Settings in) {
		require(in.baseCurrency(), "base_currency");
		if (in.fxGainLossAccountId() != null) {
			boolean usable = jdbc.sql("SELECT is_postable AND is_active FROM accounts WHERE id = ?")
					.param(in.fxGainLossAccountId()).query(Boolean.class).optional().orElse(false);
			if (!usable) {
				throw ServiceException.unprocessable("fx_gain_loss_account_id must be a postable, active account");
			}
		}
		jdbc.sql("UPDATE gl_settings SET base_currency = :baseCurrency, fx_gain_loss_account_id = :fxGainLossAccountId")
				.paramSource(in).update();
	}

	// ---- exchange rates (keyed by currency and date) ----

	/** How many base-currency units one unit of the currency buys on the date. */
	public record ExchangeRate(String currencyCode, String rateDate, String rate) {
	}

	public record ExchangeRateInput(String currencyCode, String rateDate, String rate) {
		public ExchangeRateInput {
			currencyCode = trim(currencyCode).toUpperCase(Locale.ROOT);
			rateDate = trim(rateDate);
			rate = trim(rate);
		}
	}

	public List<ExchangeRate> exchangeRates() {
		return jdbc.sql("""
				SELECT currency_code, rate_date::text AS rate_date, trim_scale(rate)::text AS rate
				FROM exchange_rates ORDER BY currency_code, rate_date DESC""").query(ExchangeRate.class).list();
	}

	/** Creates the rate and returns its key as stored. */
	public ExchangeRateInput createExchangeRate(ExchangeRateInput in) {
		require(in.currencyCode(), "currency_code");
		require(in.rateDate(), "rate_date");
		require(in.rate(), "rate");
		jdbc.sql("""
				INSERT INTO exchange_rates (currency_code, rate_date, rate)
				VALUES (:currencyCode, CAST(:rateDate AS date), CAST(:rate AS numeric))""").paramSource(in).update();
		return in;
	}

	/** Replaces the rate for the key in the path, whatever the body says of the key. */
	public void updateExchangeRate(String currency, String date, String rate) {
		ExchangeRateInput in = new ExchangeRateInput(currency, date, rate);
		require(in.rate(), "rate");
		found(jdbc.sql("""
				UPDATE exchange_rates SET rate = CAST(:rate AS numeric)
				WHERE currency_code = :currencyCode AND rate_date = CAST(:rateDate AS date)""").paramSource(in).update());
	}

	public void deleteExchangeRate(String currency, String date) {
		ExchangeRateInput in = new ExchangeRateInput(currency, date, null);
		found(jdbc.sql("DELETE FROM exchange_rates WHERE currency_code = :currencyCode AND rate_date = CAST(:rateDate AS date)")
				.paramSource(in).update());
	}
}
