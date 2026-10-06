# Stack

**Status:** adopted 2026-10-04.

## Decision

- **Back end:** Spring Boot 4.1 (Spring Framework 7) on Java 25, serving
  both the JSON API required by tadmor's `spec/api.md` and the user
  interface. Spring MVC on embedded Tomcat, Spring Security, and
  `JdbcClient` over HikariCP and pgjdbc.
- **User interface:** server-rendered Thymeleaf templates. No SPA, no npm,
  no JavaScript build step. If a small amount of client-side behaviour is
  needed, it is handwritten, or at most one vendored JavaScript file (such
  as htmx), which needs a conversation first.
- **Database:** Postgres 17 with the shared schema from tadmor's
  `db/migrations/`.
- **Build:** Maven, through a pinned Maven Wrapper (`mvnw`), as Spring
  Initializr generates it.
- **Toolchain:** OpenJDK 25 from the operating system's packages (Debian
  13 ships `openjdk-25` in trixie, and Ubuntu 26.04 has it too; the OS is
  out of scope for the metrics).

## Why Spring Boot

This counterpart is meant to be representative of how Java business
software is actually built, and that is Spring Boot, by a wide margin. As
in tadmor-php, which took Laravel over the leaner Symfony components, the
point is to measure what mainstream Java costs, not the smallest Java that
could pass the suite.

The lean alternative was considered and passed over: the JDK alone ships
an HTTP server (`com.sun.net.httpserver`), virtual threads, PBKDF2 and
TLS, so **JDK + pgjdbc** would be one jar from one publisher, plus our own
JSON, HTML templating, SMTP client and test runner. Javalin (Jetty,
Kotlin stdlib, slf4j) and Helidon SE sit in between, with no strong
argument for either.

The starters are those a typical team would pick from Spring Initializr
for this product: `webmvc`, `thymeleaf`, `security`, `jdbc`, `mail`, the
`postgresql` driver, and `spring-boot-starter-test`.

## Measured trees

Resolved on 2026-10-04 with Spring Boot 4.1.1 (published 2026-08-20) and
its BOM, by `mvn dependency:list` and a `mvn package` into an empty local
repository (Maven 3.9, Temurin 25, linux/x64).

Identities are counted by **Maven Central namespace**. Central grants
publishing rights per verified namespace, so the namespace is the
identity: a reversed domain (`org.springframework`, `org.apache`), the
user namespace of a code host (`io.github.user`), or, for a groupId
without a domain, its first part (`jakarta`). The old domainless
`commons-*` groups count as the ASF's `org.apache`. Central publishes no
account list, so an organization counts once per namespace it holds.

These figures come from `dependencies.json`, which `tools/vendor.py
manifest` writes. tadmor's `tools/measure.py` reads it, so the figures
are reproducible (`tools/measure.py ../tadmor-java`).

| | Jars | Namespaces |
| --- | ---: | ---: |
| Runtime | **68** | **17** |
| Build only (Maven plugins and their trees) | 76 | 20 (14 not in runtime) |
| Test only (`spring-boot-starter-test`) | 28 | 17 |
| Runtime + build | 144 | **31** |

An earlier hand count put the runtime at about 12 identities, by merging
namespaces one organization holds: Thymeleaf, attoparser, and unbescape
(one author, three namespaces), Jakarta and Eclipse (`jakarta`,
`org.eclipse`), QOS.ch (`ch.qos`, `org.slf4j`), and FasterXML
(`com.fasterxml`, and Jackson 3's `tools.jackson`). The namespace rule
counts each grant, because each is a separate account that can publish.
The hand count's 70 build-only jars were also an estimate.

**Runtime**, by identity:

| Identity | Jars | What |
| --- | ---: | --- |
| Spring (Broadcom), `org.springframework` | 39 | Spring Boot and its starters (25, several of them empty starter jars), Spring Framework (10), Spring Security (4) |
| Apache Software Foundation | 6 | Tomcat (core, el, websocket), log4j-api and log4j-to-slf4j, commons-logging |
| FasterXML | 3 | Jackson 3 core and databind, Jackson 2 annotations |
| QOS.ch | 4 | Logback, slf4j-api, jul-to-slf4j |
| Eclipse Foundation | 5 | Jakarta Mail, Activation, Annotation APIs; Angus Mail and Activation |
| Thymeleaf | 4 | thymeleaf, thymeleaf-spring6, attoparser, unbescape (one author) |
| Micrometer | 2 | micrometer-commons, micrometer-observation |
| pgjdbc | 1 | the Postgres driver |
| HikariCP | 1 | connection pool |
| SnakeYAML | 1 | `application.yml` parsing |
| JSpecify | 1 | nullness annotations |
| Checker Framework | 1 | `checker-qual` annotations (via pgjdbc) |

The runtime jars total 28.9 MB.

**Build only.** Maven resolves its plugins at build time, and they are
third-party code that runs with full access on every developer and CI
machine: the compiler, resources, surefire, jar and install plugins,
Spring Boot's repackaging plugin, and their trees (Plexus, Sisu, Maven
Shared, Commons, HttpComponents, ASM, JNA, zstd-jni, xz, QDox, JDOM,
tomlj, jdependency, and others). About half the build-only identities are
independent maintainers. The Maven distribution itself, fetched by the
wrapper, is a toolchain publisher (the Apache Maven project) and is listed,
not counted, like pnpm in tadmor.

**Test only.** JUnit Jupiter and Platform (with opentest4j and
apiguardian, all the JUnit team), Mockito with ByteBuddy and Objenesis,
AssertJ, Hamcrest, Awaitility, JSONassert (with Vaadin's android-json),
Jayway JsonPath (with json-smart), XMLUnit, and ASM.

For comparison (runtime figures by tadmor's `tools/measure.py`, from each repository's manifest, 2026-10-05; tadmor-php's from its own docs until it has a manifest):

| Implementation | Runtime packages | Runtime identities |
| --- | ---: | ---: |
| tadmor-java (Spring Boot, JDBC) | 68 | 17 namespaces |
| tadmor-python (Django) | 5 | 7 PyPI accounts |
| tadmor-dotnet (ASP.NET Core, EF Core) | 6 | 8 NuGet accounts |
| tadmor-php (Laravel) | 73 | 37 accounts |
| tadmor (runtime only) | 99 | 58 |

## Why JDBC rather than JPA

Spring Data JPA with Hibernate is the more common default, and was
measured: it takes the runtime tree from **68 to 91 jars and from about
12 to about 17 identities** (Hibernate ORM and Models, JBoss Logging,
ByteBuddy, ANTLR, AspectJ, Jakarta Persistence, Transaction and Inject,
and the GlassFish JAXB runtime). It was passed over because the shared
schema is not ours to shape. Much of the domain lives in views, generated
columns and constraint triggers, and the reports are SQL. Entities mapped
onto that schema would be read-mostly wrappers, and most real queries
would end up native anyway. `JdbcClient` over that SQL is also a
mainstream Spring idiom, and it keeps this counterpart's data access
comparable with tadmor's own.

## Why server-rendered templates

As in tadmor-python and tadmor-php: Thymeleaf is the template engine
Spring Boot supports out of the box, and a server-rendered UI removes the
npm tree entirely rather than reproducing it. `spec/README.md` allows
server-rendered pages; the JSON API remains mandatory alongside them.

## Permitted packages

The starters above, with exactly the trees their 4.1.1 BOM resolves, plus
the Maven plugins the build uses, and nothing else without a conversation
first. In particular:

- **No Spring Data JPA or Hibernate** (above).
- **No Flyway or Liquibase.** The shared migrations follow golang-migrate
  naming (`000001_init.up.sql`), which neither tool reads, so a small
  runner of our own applies them (below).
- **No Lombok, MapStruct, or other annotation processors.** Records cover
  the data carriers.
- **No `spring-boot-devtools`, Actuator, or `spring-boot-starter-validation`**
  (Hibernate Validator). `/healthz` and `/readyz` are two handlers;
  request validation is in the service layer, where the spec's error
  messages are produced.
- **No Testcontainers.** Tests use `TEST_DATABASE_URL`, as tadmor does.
- **No CSV library** (Commons CSV, OpenCSV). Bank statement import needs
  only a small RFC 4180 reader, about a hundred lines in `service/Csv.java`,
  kept to the behavior of tadmor's Go `encoding/csv`.
- **No Gradle.** Maven is the more widely used of the two, and the one
  Initializr defaults to.

## Supply-chain posture

- **Vendored and committed.** The local Maven repository the build
  resolves, holding the runtime, test and plugin artifacts with their
  POMs (1,040 files, 77 MB), is committed under `vendor/`, and the Makefile runs
  Maven offline (`-o`) against it. A clean clone therefore builds with no
  access to Maven Central. Unlike Go and PHP, what is vendored is
  compiled bytecode, not source, so vendoring buys integrity and
  hermeticity rather than reviewability.
- **Pinned.** Every version comes from the Spring Boot BOM or is written
  exactly in `pom.xml`, with no ranges. Maven has no lockfile and no
  integrity hashes of its own, so the committed repository is the
  integrity guard. It is populated with strict checksum checking (`-C`),
  and a dependency change is reviewable as a `pom.xml` diff plus a
  `vendor/` diff.
- **Maven Wrapper.** `mvnw` pins the Maven version and the distribution's
  `distributionSha256Sum`. The distribution is a toolchain download, like
  pnpm through corepack in tadmor.
- **No install-time code.** Maven has no install scripts. Plugins do run
  code at build time, which is why they are counted as build dependencies
  above.
- **Cooldown.** No version published less than 7 days ago, as tadmor's
  pnpm policy requires. Maven has no such setting, so the vendoring script
  checks each new artifact's publication date on Central before adding it.
- **Hermetic build: level 4.** Measured 2026-10-04 at the first skeleton
  commit: two fresh clones, each built with `mvnw -o` in a container with
  `--network=none`, given only a JDK 25 and the wrapper's Maven
  distribution (mounted read-only), produced byte-identical
  `target/tadmor.jar`s. Measured again on 2026-10-06 at `4fad909`, with
  the API and UI complete, in the `eclipse-temurin:25-jdk` image: the
  two jars were again byte-identical. Maven's `project.build.outputTimestamp` makes this
  possible. Identical output needs the same JDK build; a different vendor's
  `javac` may produce different class files.

## Consequences for the shared schema

The users and sessions tables the spec relies on are defined by the shared
schema, so Spring adds no tables of its own. Migrations are applied at
startup by a small runner of our own that follows `spec/README.md` (every
`*.up.sql` in lexical order, each in its own transaction, recorded in
`schema_migrations`). Every connection runs in UTC.

## Authentication

Spring Security does the work it is usually trusted with, and the shared
tables do the storage:

- **Credentials.** `DaoAuthenticationProvider` over a `UserDetailsService`
  that reads the shared `users` table. It finds only active users, so a
  deactivated user is "not found" rather than "disabled" and gets the same
  dummy password check as an unknown email. All three login failures take
  the same time, as `api.md` §3 asks.
- **Password hashes.** Spring Security's default `DelegatingPasswordEncoder`,
  which is bcrypt with the scheme recorded in the hash (`{bcrypt}...`).
  tadmor uses PBKDF2; the scheme is not contract (`domain.md` §12).
- **Sessions.** tadmor's model: a random token in an `HttpOnly`,
  `SameSite=Lax` cookie (`tadmor_session`), stored only as its SHA-256 in
  `sessions`, valid for a fixed 30 days. A custom
  `SecurityContextRepository` resolves the cookie on each request,
  rereading the user so that deactivation and demotion take effect
  immediately. There is no `HttpSession` (the policy is `STATELESS`) and no
  Spring Session.
- **One session for API and UI.** The JSON login and the UI's login form
  share the credential check and the cookie. An unauthenticated API call
  gets a JSON 401; an unauthenticated page redirects to `/login`.
- **CSRF.** UI forms carry Spring Security's token, kept in a cookie
  (`CookieCsrfTokenRepository`) so that no `HttpSession` is needed. The API
  is exempt, relying on the `SameSite=Lax` session cookie as tadmor does.

One pgjdbc trap is worth recording: pgjdbc sends string parameters as
`varchar`, and `citext = varchar` resolves to case-sensitive text equality.
Queries on citext columns (emails) therefore cast the parameter
(`email = ?::citext`), which tadmor's driver never needed.

## The user interface

Server-rendered Thymeleaf pages over the same services as the JSON API, so
the UI enforces exactly the API's rules and shows the server's messages
(`spec/domain.md` §13 G5). Records convert to and from the API's snake_case
JSON shapes through the same Jackson configuration, so templates and forms
use the spec's field names, and simple record types share one generic list
and form (`ui/Resources`). One stylesheet and one handwritten script,
`app.js`, both adapted from tadmor-python's: the script is the line editor
(adding lines, filling them from products and tax codes, and previewing
totals with exact BigInt decimals rounded as the server does) and the
delete confirmations. A same-origin Content Security Policy forbids inline
script and style. Administrator actions are gated in `SecurityConfig`, the
UI's paths mirroring the API's, so templates only hide them.

## Printing

tadmor writes its PDFs by hand with the standard-14 Helvetica fonts, so no
library is needed. This counterpart ports that writer to Java (using
`java.util.zip.Deflater` and the `windows-1252` charset), as tadmor-python
and tadmor-php do, and copies the font widths from tadmor's generated
table. Email goes through Spring's `JavaMailSender` (Jakarta Mail, already
in the tree) when SMTP is configured, and is otherwise refused with 501.

## What would make these choices worth revisiting

- **Maven support in `tools/measure.py`.** Once tadmor's tool counts Maven
  Central namespaces, replace the hand counts above with its output.
- **A second script or a richer client.** If more screens need client-side
  behaviour than the line editor, htmx (one vendored file) is the first
  candidate to discuss.
- **Spring Boot's own direction.** If a later Boot release changes what
  the chosen starters pull in (as 4.0 did by modularizing autoconfigure),
  re-measure before upgrading.
