The goal of this project is to develop comprehensive business management software.
It is the Java counterpart of tadmor (~/tadmor): the same product, specified by
tadmor's spec/ and checked by its conformance/ suite, built on a different stack so
the two can be compared (see ~/tadmor/docs/counterpart-metrics.md).

Technology stack:
Postgres for the database, using the shared schema from tadmor's db/migrations.
Java 25 and Spring Boot 4.1 for the back end: Spring MVC, Spring Security, JdbcClient.
Server-rendered Thymeleaf templates for the user interface; no npm, no JavaScript build.
Maven (via mvnw) for the build.
See docs/stack.md for the decision and its rationale.

Schema design:
The schema is shared with tadmor and is not ours to redesign. Data access is SQL through
JdbcClient; no JPA, no Flyway or Liquibase. Our own migration runner applies db/migrations.

Dependencies:
Supply-chain conscious, but representative of mainstream Spring Boot. The only permitted
third-party artifacts are the starters listed in docs/stack.md with the trees the Spring
Boot BOM resolves for them, plus the Maven plugins the build uses. New dependencies need
a conversation first. The resolved Maven repository is committed under vendor/, and the
build runs offline against it; nothing is fetched from Maven Central at build or run time.

Working on it:
Business rules live in a service layer shared by the JSON API and the HTML UI.
Never put a rule in a controller.
spec/, conformance/, and db/migrations/ are copies from tadmor (spec/UPSTREAM);
never edit them here. Re-export from tadmor with spec/export.sh.

Version control:
Commit directly to the default branch. Do not create feature branches.
