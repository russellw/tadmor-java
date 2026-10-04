# tadmor-java developer tasks.
#
# Maven runs offline against the committed repository in vendor/: nothing is
# fetched from Maven Central, and no target touches the network except
# vendor-sync. The wrapper (mvnw) fetches its pinned Maven distribution once.
MVN := ./mvnw -B -o -Dmaven.repo.local=$(CURDIR)/vendor
JAVA ?= java

# Connection strings. Override on the command line, e.g.
#   make run DATABASE_URL=postgres://user:pass@host:5432/db
DATABASE_URL ?= postgres://tadmor:tadmor@127.0.0.1:5432/tadmor_java
TEST_DATABASE_URL ?= postgres://tadmor:tadmor@127.0.0.1:5432/tadmor_java_test
CONFORMANCE_DATABASE_URL ?= postgres://tadmor:tadmor@127.0.0.1:5432/tadmor_java_conformance
HTTP_ADDR ?= 127.0.0.1:8080

.DEFAULT_GOAL := help
.PHONY: help build run adduser test conformance clean vendor-check vendor-sync db

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN{FS=":.*## "}{printf "  make %-13s %s\n", $$1, $$2}'

build: ## Build target/tadmor.jar without running the tests
	$(MVN) package -DskipTests

run: build ## Build and run the server (migrates on start)
	DATABASE_URL=$(DATABASE_URL) HTTP_ADDR=$(HTTP_ADDR) $(JAVA) -jar target/tadmor.jar

adduser: build ## Create or reset an administrator: make adduser EMAIL=... NAME=... (password on stdin)
	DATABASE_URL=$(DATABASE_URL) $(JAVA) -jar target/tadmor.jar adduser --email="$(EMAIL)" --name="$(NAME)"

test: ## Run the test suite (integration tests wipe TEST_DATABASE_URL)
	TEST_DATABASE_URL=$(TEST_DATABASE_URL) $(MVN) package

conformance: build ## Run tadmor's conformance suite against a fresh server (wipes the _conformance DB)
	DATABASE_URL=$(CONFORMANCE_DATABASE_URL) JAVA=$(JAVA) tools/conformance.sh $(ARGS)

clean: ## Remove build output
	rm -rf target

vendor-check: ## Verify vendor/ matches vendor/lock.txt (offline)
	tools/vendor.py check

vendor-sync: ## Re-resolve vendor/ from Maven Central and rewrite the lock (network)
	tools/vendor.py sync

db: ## Start a local Postgres 17 container (podman) with the dev and test databases
	podman run -d --name tadmor-java-pg -e POSTGRES_USER=tadmor -e POSTGRES_PASSWORD=tadmor \
		-e POSTGRES_DB=tadmor_java -p 127.0.0.1:5432:5432 docker.io/library/postgres:17
	until podman exec tadmor-java-pg pg_isready -U tadmor -q; do sleep 1; done
	podman exec tadmor-java-pg createdb -U tadmor tadmor_java_test
	podman exec tadmor-java-pg createdb -U tadmor tadmor_java_conformance
