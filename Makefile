# ── KotAuth developer Makefile ────────────────────────────────────────────────
# Run `make help` to list all available targets.
#
# Requires: Java 17+, Gradle wrapper (./gradlew), Docker, Docker Compose.
# CSS compilation also requires Node.js 20+ on first run (npm ci is automatic).
# ─────────────────────────────────────────────────────────────────────────────

.DEFAULT_GOAL := help
.PHONY: help css css-admin css-auth lint lint-fix test build jar version up up-fresh down nuke logs health

# ── CSS ───────────────────────────────────────────────────────────────────────

css: ## Compile both CSS bundles (admin + auth)
	./gradlew compileCssAdmin compileCssAuth

css-admin: ## Compile the admin console CSS bundle only
	./gradlew compileCssAdmin

css-auth: ## Compile the auth pages CSS bundle only
	./gradlew compileCssAuth

# ── Kotlin ────────────────────────────────────────────────────────────────────

version: ## Generate version.properties resource (required before running from IDE)
	./gradlew generateVersionProperties

lint: ## Run ktlint check (all .kt except *View.kt)
	./gradlew ktlintCheck

lint-fix: ## Auto-fix lint issues with ktlintFormat
	./gradlew ktlintFormat

test: ## Run the test suite
	./gradlew test

build: ## Full build — CSS + lint + tests + fat JAR (CI-equivalent)
	./gradlew build

jar: ## Build fat JAR only, skipping tests (faster iteration)
	./gradlew buildFatJar -x test

# ── Docker ────────────────────────────────────────────────────────────────────
# Makefile targets use docker/docker-compose.dev.yml (build from source).
# To run the pre-built image instead:
#   docker compose -f docker/docker-compose.yml up -d

up: ## Build images from source and start all services (dev)
	docker compose -f docker/docker-compose.dev.yml up -d --build

up-fresh: ## Rebuild dev images from scratch (no layer cache)
	docker compose -f docker/docker-compose.dev.yml build --no-cache && docker compose -f docker/docker-compose.dev.yml up -d

down: ## Stop and remove containers
	docker compose -f docker/docker-compose.dev.yml down

nuke: ## Stop containers and wipe volumes (destroys the database)
	docker compose -f docker/docker-compose.dev.yml down -v

logs: ## Follow app container logs
	docker compose -f docker/docker-compose.dev.yml logs -f app

health: ## Probe the local health endpoint
	@curl -sf http://localhost:8080/health/ready && echo " OK" || echo " FAILED"

# ── Help ──────────────────────────────────────────────────────────────────────

help: ## Show this help message
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""
