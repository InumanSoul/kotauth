# ── KotAuth developer Makefile ────────────────────────────────────────────────
# Run `make help` to list all available targets.
#
# Requires: Java 17+, Gradle wrapper (./gradlew), Docker, Docker Compose.
# CSS compilation also requires Node.js 20+ on first run (npm ci is automatic).
# ─────────────────────────────────────────────────────────────────────────────

.DEFAULT_GOAL := help
.PHONY: help css css-admin css-auth js lint lint-fix test e2e build jar version up up-fresh down nuke logs health

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

test: ## Run the test suite
	./gradlew test

e2e: ## Run E2E browser smoke tests (Playwright, headless)
	./gradlew e2eTest

e2e-headed: ## Run E2E tests with visible browser (debugging)
	./gradlew e2eTest -Dplaywright.headless=false

build: ## Full build — CSS + lint + tests + fat JAR (CI-equivalent)
	./gradlew build

jar: ## Build fat JAR only, skipping tests (faster iteration)
	./gradlew buildFatJar -x test

# ── Docker ────────────────────────────────────────────────────────────────────
# --project-directory . ensures Docker Compose reads .env and resolves env_file
# paths from the repo root, not from the docker/ subdirectory.
# Makefile targets use docker/docker-compose.dev.yml (build from source).
# To run the pre-built image instead:
#   docker compose --project-directory . -f docker/docker-compose.yml up -d

COMPOSE_DEV = docker compose --project-directory . -f docker/docker-compose.dev.yml

up: ## Build images from source and start all services (dev)
	$(COMPOSE_DEV) up -d --build

up-fresh: ## Rebuild dev images from scratch (no layer cache)
	$(COMPOSE_DEV) build --no-cache && $(COMPOSE_DEV) up -d

down: ## Stop and remove containers
	$(COMPOSE_DEV) down

nuke: ## Stop containers and wipe volumes (destroys the database)
	$(COMPOSE_DEV) down -v

logs: ## Follow app container logs
	$(COMPOSE_DEV) logs -f app

health: ## Probe the local health endpoint
	@curl -sf http://localhost:8080/health/ready && echo " OK" || echo " FAILED"

# ── Help ──────────────────────────────────────────────────────────────────────

help: ## Show this help message
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""
