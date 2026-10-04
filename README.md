# tadmor-java

Business management software: the Java counterpart of
[tadmor](https://github.com/russellw/tadmor). It is the same product, specified
by tadmor's `spec/` and checked by its conformance suite, built on Spring Boot
with server-rendered pages. The counterparts exist to compare supply-chain
exposure, and this one measures mainstream Spring Boot as teams actually use
it; see [`docs/stack.md`](docs/stack.md).

**Status:** early. The server migrates the shared schema on start, serves
the probes, has login, logout, and sessions for both the JSON API and the
UI, and has the users, master data, calendar, and settings APIs. Documents,
posting, and the rest of the UI are still to come; `make conformance` passes
13 of its 35 cases.

## Layout

```
src/main/java/com/belunaro/tadmor/
  AddUser, ResetDb command-line tools (java -jar tadmor.jar adduser|resetdb)
  db/              connection pool from DATABASE_URL; the migration runner
  security/        Spring Security: credentials, sessions, the filter chain
  service/         business rules, shared by the JSON API and the UI
  api/             the JSON API of spec/api.md
  ui/              the server-rendered UI of spec/domain.md §13
  web/             probes and the error page, shared by both
src/main/resources/   application.properties, Thymeleaf templates
src/test/java/     JUnit tests (integration tests use TEST_DATABASE_URL)
spec/, conformance/, db/migrations/   copied from tadmor (spec/UPSTREAM); never edited here
vendor/            the Maven repository the build resolves, committed (vendor/lock.txt)
tools/             vendor.py (vendoring), conformance.sh (suite wrapper)
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
| `JAVA` | `java` | Java launcher the Makefile and `tools/conformance.sh` use |

Endpoints so far: `GET /healthz` (liveness), `GET /readyz` (database
reachable), `/api/auth/*`, `/api/users`, and master data: organizations,
customers, suppliers, products, accounts (with their ledgers), tax codes,
payment terms, and warehouses; fiscal years and accounting periods; and
the ledger settings and exchange rates (spec/api.md §3, §5.1 to §5.8, less
year-end close and reopen, which come with posting).

## Build, run, test

```sh
make db              # once: a local Postgres in a container
make adduser EMAIL=me@example.com NAME='My Name'    # password on stdin; migrates first
make build           # target/tadmor.jar, offline from vendor/
make run             # build and run on 127.0.0.1:8080
make test            # the JUnit suite
make conformance     # tadmor's conformance suite, from a wiped database
make vendor-check    # vendor/ against vendor/lock.txt
```

> The integration tests **drop and recreate the `public` schema** of
> `TEST_DATABASE_URL`. Point it only at a throwaway database.

## Dependencies

Change dependencies only by editing `pom.xml` and running
`make vendor-sync`, which re-resolves `vendor/` from scratch, refuses any
version less than 7 days old, and rewrites `vendor/lock.txt`. New
dependencies need a conversation first (`docs/stack.md`).
