# ── KotAuth developer Makefile ────────────────────────────────────────────────
# Run `make help` to list all available targets.
#
# Requires: Java 17+, Gradle wrapper (./gradlew), Docker, Docker Compose.
# CSS compilation also requires Node.js 20+ on first run (npm ci is automatic).
# ─────────────────────────────────────────────────────────────────────────────

.DEFAULT_GOAL := help
.PHONY: help css css-admin css-auth js lint lint-fix detekt detekt-baseline test test-redis test-postgres e2e build jar version up up-fresh down nuke logs health generate-key reset-mfa generate-api-key run infra-up update-locks

# ── CSS ───────────────────────────────────────────────────────────────────────

css: ## Compile both CSS bundles (admin + auth)
	./gradlew compileCssAdmin compileCssAuth compileCssPortalSidenav compileCssPortalTabnav

css-admin: ## Compile the admin console CSS bundle only
	./gradlew compileCssAdmin

css-auth: ## Compile the auth pages CSS bundle only
	./gradlew compileCssAuth

# ── JS ────────────────────────────────────────────────────────────────────────

js: ## Compile all JS bundles and generate SRI hashes
	./gradlew compileJs generateJsSri

css-portal: ## Compile the portal CSS bundles (sidenav + tabnav)
	./gradlew compileCssPortalSidenav compileCssPortalTabnav
# ── Kotlin ────────────────────────────────────────────────────────────────────

version: ## Generate version.properties resource (required before running from IDE)
	./gradlew generateVersionProperties

lint: ## Run ktlint check (all .kt except *View.kt)
	./gradlew ktlintCheck

lint-fix: ## Auto-fix lint issues with ktlintFormat
	./gradlew ktlintFormat

detekt: ## Run detekt complexity/code-smell analysis
	./gradlew detekt

detekt-baseline: ## Regenerate the detekt baseline (accepts current debt)
	./gradlew detektBaseline

test: ## Run the test suite (no Docker required)
	./gradlew test

test-redis: ## Run Redis-backed integration tests (Testcontainers, Docker required)
	@DOCKER_HOST=$$(docker context inspect --format '{{.Endpoints.docker.Host}}') \
	  DOCKER_API_VERSION=1.43 \
	  TESTCONTAINERS_RYUK_DISABLED=true \
	  ./gradlew redisTest

test-postgres: ## Run Postgres-backed integration tests (Testcontainers, Docker required)
	@DOCKER_HOST=$$(docker context inspect --format '{{.Endpoints.docker.Host}}') \
	  DOCKER_API_VERSION=1.43 \
	  TESTCONTAINERS_RYUK_DISABLED=true \
	  ./gradlew postgresTest

e2e: ## Run E2E browser smoke tests (Playwright, headless)
	./gradlew e2eTest

e2e-headed: ## Run E2E tests with visible browser (debugging)
	./gradlew e2eTest -Dplaywright.headless=false

build: ## Full build — CSS + lint + tests + fat JAR (CI-equivalent)
	./gradlew build

jar: ## Build fat JAR only, skipping tests (faster iteration)
	./gradlew buildFatJar -x test

update-locks: ## Regenerate gradle.lockfile (run after bumping any dependency)
	./gradlew dependencies --write-locks
	@echo "gradle.lockfile updated — commit it with your dependency change."

# ── Docker ────────────────────────────────────────────────────────────────────

COMPOSE = docker compose

up: ## Build the image from local source and start the stack
	$(COMPOSE) up -d --build

up-fresh: ## Rebuild from scratch (no Docker layer cache)
	$(COMPOSE) build --no-cache && $(COMPOSE) up -d

down: ## Stop and remove containers
	$(COMPOSE) down

nuke: ## Stop containers and wipe volumes (destroys the database)
	$(COMPOSE) down -v

infra-up: ## Start only db + redis in Docker (for `make run`)
	$(COMPOSE) --profile redis up -d --wait db redis

run: infra-up ## Run Kotauth on the host JVM against Docker-hosted db + redis
	@env \
	  KAUTH_BASE_URL=http://localhost:8080 \
	  KAUTH_ENV=development \
	  KAUTH_SECRET_KEY=dev-only-not-for-production-replace-this-secret-key \
	  DB_HOST=localhost \
	  DB_PORT=5432 \
	  DB_USER=kotauth \
	  DB_PASSWORD=localonly \
	  DB_NAME=kotauth_db \
	  KAUTH_REDIS_URL=redis://localhost:6379 \
	  KAUTH_I18N_BUNDLE_DIR=docs/i18n \
	  ./gradlew run

logs: ## Follow app container logs
	$(COMPOSE) logs -f app

health: ## Probe the local health endpoint
	@curl -sf http://localhost:8080/health/ready && echo " OK" || echo " FAILED"

# ── CLI ──────────────────────────────────────────────────────────────────────

generate-key: ## Generate a cryptographically secure KAUTH_SECRET_KEY
	@java -jar build/libs/kotauth-all.jar cli generate-secret-key

reset-mfa: ## Reset MFA for an admin user (usage: make reset-mfa USER=admin)
	@java -jar build/libs/kotauth-all.jar cli reset-admin-mfa --username=$(USER)

generate-api-key: ## Mint a bootstrap API key + SHA-256 (usage: make generate-api-key TENANT=zion)
	@java -jar build/libs/kotauth-all.jar cli hash-api-key --tenant=$(TENANT)

# ── Help ──────────────────────────────────────────────────────────────────────

help: ## Show this help message
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""
