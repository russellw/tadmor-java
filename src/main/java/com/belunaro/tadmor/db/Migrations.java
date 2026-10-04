package com.belunaro.tadmor.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the shared schema in db/migrations (carried in the jar) as
 * spec/README.md requires: every *.up.sql in lexical order, each in its own
 * transaction together with the schema_migrations row that records it, so a
 * failed migration leaves no trace. It runs while the application context
 * starts, before the web server accepts requests, as tadmor migrates on
 * startup.
 */
@Component
public class Migrations implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(Migrations.class);

	private static final String LOCATION = "classpath:db/migrations/*.up.sql";

	private final JdbcClient jdbc;
	private final JdbcTemplate template;
	private final TransactionTemplate tx;

	public Migrations(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.template = template;
		this.tx = tx;
	}

	@Override
	public void afterPropertiesSet() throws IOException {
		List<String> applied = apply();
		if (!applied.isEmpty()) {
			log.info("applied migrations {}", applied);
		}
	}

	/** Applies the pending migrations and returns the versions newly applied. */
	public List<String> apply() throws IOException {
		jdbc.sql("""
				CREATE TABLE IF NOT EXISTS schema_migrations (
				    version    text        PRIMARY KEY,
				    applied_at timestamptz NOT NULL DEFAULT now()
				)""").update();
		Set<String> done = new HashSet<>(jdbc.sql("SELECT version FROM schema_migrations").query(String.class).list());

		Resource[] files = new PathMatchingResourcePatternResolver().getResources(LOCATION);
		if (files.length == 0) {
			// A broken build, not an up-to-date schema.
			throw new IllegalStateException("no *.up.sql migration files found at " + LOCATION);
		}
		Arrays.sort(files, Comparator.comparing(Resource::getFilename));

		List<String> applied = new ArrayList<>();
		for (Resource file : files) {
			String version = file.getFilename().substring(0, file.getFilename().length() - ".up.sql".length());
			if (done.contains(version)) {
				continue;
			}
			String sql = file.getContentAsString(StandardCharsets.UTF_8);
			tx.executeWithoutResult(status -> {
				// A plain Statement, so that nothing in the file is taken for
				// a parameter placeholder; pgjdbc runs it as one batch,
				// pl/pgsql bodies and all.
				template.execute(sql);
				jdbc.sql("INSERT INTO schema_migrations (version) VALUES (?)").param(version).update();
			});
			applied.add(version);
		}
		return applied;
	}
}
