# tadmor-java

Business management software: the Java counterpart of
[tadmor](https://github.com/russellw/tadmor). It is the same product, specified
by tadmor's `spec/` and checked by its conformance suite, built on Spring Boot
with server-rendered pages. The counterparts exist to compare supply-chain
exposure, and this one measures mainstream Spring Boot as teams actually use
it; see [`docs/stack.md`](docs/stack.md).

**Status:** project skeleton. The server migrates the shared schema on start
and serves the probes; the API and UI are still to come.

## Layout

```
src/main/java/com/belunaro/tadmor/
  db/              connection pool from DATABASE_URL; the migration runner
  web/             HTTP handlers
  security/        the Spring Security filter chain
src/main/resources/   application.properties, Thymeleaf templates
src/test/java/     JUnit tests (integration tests use TEST_DATABASE_URL)
spec/, conformance/, db/migrations/   copied from tadmor (spec/UPSTREAM); never edited here
vendor/            the Maven repository the build resolves, committed (vendor/lock.txt)
tools/             vendor.py (vendoring)
docs/              decisions and notes
```

## Prerequisites

- **OpenJDK 25**: `sudo apt install openjdk-25-jdk-headless` (Debian 13 and
  Ubuntu 26.04 both package it).
- **Postgres 17**, reachable via `DATABASE_URL`. `make db` starts one in a
  container.
- **Go**, only to run the conformance suite.

No dependency download step: every artifact the build uses is already in
`vendor/`. The first build fetches the pinned Maven distribution through
`mvnw` (checked against its sha256) into `~/.m2/wrapper`.

## Configuration

| Env var | Default | Purpose |
| ------- | ------- | ------- |
| `DATABASE_URL` | none (required) | Postgres connection string, libpq form: `postgres://user:pass@host:port/db?sslmode=disable` |
| `HTTP_ADDR` | `:8080` | Listen address, `host:port` |
| `PORT` | unset | Listen port; overrides the port in `HTTP_ADDR` (Cloud Run injects it) |
| `TEST_DATABASE_URL` | none | Database the integration tests wipe and use |

Endpoints so far: `GET /healthz` (liveness) and `GET /readyz` (database
reachable).

## Build, run, test

```sh
make db              # once: a local Postgres in a container
make build           # target/tadmor.jar, offline from vendor/
make run             # build and run on 127.0.0.1:8080
make test            # the JUnit suite
make vendor-check    # vendor/ against vendor/lock.txt
```

> The integration tests **drop and recreate the `public` schema** of
> `TEST_DATABASE_URL`. Point it only at a throwaway database.

## Dependencies

Change dependencies only by editing `pom.xml` and running
`make vendor-sync`, which re-resolves `vendor/` from scratch, refuses any
version less than 7 days old, and rewrites `vendor/lock.txt`. New
dependencies need a conversation first (`docs/stack.md`).
