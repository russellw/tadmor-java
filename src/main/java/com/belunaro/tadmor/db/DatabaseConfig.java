package com.belunaro.tadmor.db;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The connection pool, configured from DATABASE_URL in the libpq URL form
 * every tadmor implementation shares, rather than from Spring's
 * spring.datasource.* properties.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseConfig {

	@Bean
	public DataSource dataSource(@Value("${tadmor.database-url:}") String databaseUrl) {
		if (databaseUrl.isBlank()) {
			throw new IllegalStateException("DATABASE_URL is required");
		}
		PostgresUrl url = PostgresUrl.parse(databaseUrl);
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(url.jdbcUrl());
		config.setUsername(url.user());
		config.setPassword(url.password());
		// The shared schema's aging views and default dates use current_date,
		// which follows the session's timezone (spec/README.md).
		config.setConnectionInitSql("SET TIME ZONE 'UTC'");
		// Fail /readyz promptly rather than after Hikari's 30 s default.
		config.setConnectionTimeout(5_000);
		config.setPoolName("tadmor");
		return new HikariDataSource(config);
	}
}
