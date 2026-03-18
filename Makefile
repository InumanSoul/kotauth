# ── KotAuth developer Makefile ────────────────────────────────────────────────
# Run `make help` to list all available targets.
#
# Requires: Java 17+, Gradle wrapper (./gradlew), Docker, Docker Compose.
# CSS compilation also requires Node.js 20+ on first run (npm ci is automatic).
# ─────────────────────────────────────────────────────────────────────────────

.DEFAULT_GOAL := help
.PHONY: help css css-admin css-auth lint lint-fix test build jar up up-fresh down nuke logs health

# ── CSS ───────────────────────────────────────────────────────────────────────

css: ## Compile both CSS bundles (admin + auth)
	./gradlew compileCssAdmin compileCssAuth

css-admin: ## Compile the admin console CSS bundle only
	./gradlew compileCssAdmin

css-auth: ## Compile the auth pages CSS bundle only
	./gradlew compileCssAuth

# ── Kotlin ────────────────────────────────────────────────────────────────────

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

up: ## Build images and start all services
	docker compose up --build

up-fresh: ## Rebuild everything from scratch (no Docker layer cache)
	docker compose up --build --no-cache

down: ## Stop and remove containers
	docker compose down

nuke: ## Stop containers and wipe volumes (destroys the database)
	docker compose down -v

logs: ## Follow app container logs
	docker compose logs -f app

health: ## Probe the local health endpoint
	@curl -sf http://localhost:8080/health/ready && echo " OK" || echo " FAILED"

# ── Help ──────────────────────────────────────────────────────────────────────

help: ## Show this help message
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""
