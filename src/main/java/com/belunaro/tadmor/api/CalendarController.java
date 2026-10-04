package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.CalendarService;
import com.belunaro.tadmor.service.CalendarService.AccountingPeriod;
import com.belunaro.tadmor.service.CalendarService.AccountingPeriodInput;
import com.belunaro.tadmor.service.CalendarService.FiscalYear;
import com.belunaro.tadmor.service.CalendarService.FiscalYearInput;
import com.belunaro.tadmor.service.SettingsService;
import com.belunaro.tadmor.service.SettingsService.ExchangeRate;
import com.belunaro.tadmor.service.SettingsService.ExchangeRateInput;
import com.belunaro.tadmor.service.SettingsService.Settings;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fiscal years and accounting periods (spec/api.md §5.7), and the ledger
 * settings and exchange rates (§5.8). Changing the settings is for
 * administrators only (SecurityConfig).
 */
@RestController
@RequestMapping("/api")
public class CalendarController {

	private final CalendarService calendar;
	private final SettingsService settings;

	public CalendarController(CalendarService calendar, SettingsService settings) {
		this.calendar = calendar;
		this.settings = settings;
	}

	@GetMapping("/fiscal-years")
	public List<FiscalYear> fiscalYears() {
		return calendar.fiscalYears();
	}

	@GetMapping("/fiscal-years/{id}")
	public FiscalYear fiscalYear(@PathVariable Id id) {
		return calendar.fiscalYear(id.value());
	}

	@PostMapping("/fiscal-years")
	public ResponseEntity<Map<String, Object>> createFiscalYear(@RequestBody FiscalYearInput in) {
		return Responses.createdId(calendar.createFiscalYear(in));
	}

	@PutMapping("/fiscal-years/{id}")
	public ResponseEntity<Void> updateFiscalYear(@PathVariable Id id, @RequestBody FiscalYearInput in) {
		calendar.updateFiscalYear(id.value(), in);
		return Responses.noContent();
	}

	@GetMapping("/accounting-periods")
	public List<AccountingPeriod> periods() {
		return calendar.periods();
	}

	@GetMapping("/accounting-periods/{id}")
	public AccountingPeriod period(@PathVariable Id id) {
		return calendar.period(id.value());
	}

	@PostMapping("/accounting-periods")
	public ResponseEntity<Map<String, Object>> createPeriod(@RequestBody AccountingPeriodInput in) {
		return Responses.createdId(calendar.createPeriod(in));
	}

	@PutMapping("/accounting-periods/{id}")
	public ResponseEntity<Void> updatePeriod(@PathVariable Id id, @RequestBody AccountingPeriodInput in) {
		calendar.updatePeriod(id.value(), in);
		return Responses.noContent();
	}

	@GetMapping("/settings")
	public Settings settings() {
		return settings.settings();
	}

	@PutMapping("/settings")
	public ResponseEntity<Void> updateSettings(@RequestBody Settings in) {
		settings.updateSettings(in);
		return Responses.noContent();
	}

	@GetMapping("/exchange-rates")
	public List<ExchangeRate> exchangeRates() {
		return settings.exchangeRates();
	}

	@PostMapping("/exchange-rates")
	public ResponseEntity<Map<String, String>> createExchangeRate(@RequestBody ExchangeRateInput in) {
		ExchangeRateInput key = settings.createExchangeRate(in);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("currency_code", key.currencyCode(), "rate_date", key.rateDate()));
	}

	public record RateUpdate(String rate) {
	}

	@PutMapping("/exchange-rates/{currency}/{date}")
	public ResponseEntity<Void> updateExchangeRate(@PathVariable String currency, @PathVariable String date,
			@RequestBody RateUpdate in) {
		settings.updateExchangeRate(currency, date, in.rate());
		return Responses.noContent();
	}

	@DeleteMapping("/exchange-rates/{currency}/{date}")
	public ResponseEntity<Void> deleteExchangeRate(@PathVariable String currency, @PathVariable String date) {
		settings.deleteExchangeRate(currency, date);
		return Responses.noContent();
	}
}
