# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotauth is a self-hosted identity and access management server built with Kotlin/Ktor. It provides multi-tenant authentication, authorization, user management, OAuth2/OIDC, MFA, and an admin UI.

**Stack**: Kotlin 1.9.24 · Ktor 2.3.12 · Exposed 0.50.1 (ORM) · PostgreSQL 15 · JVM 17+ · LightningCSS (build-time only)

## Common Commands

| Task | Command |
|---|---|
| Build + start Docker stack | `make up` |
| Full CI build (CSS + lint + test + JAR) | `make build` |
| Fast JAR (skip tests) | `make jar` |
| Run all tests | `make test` |
| Run single test class | `./gradlew test --tests com.kauth.domain.service.AuthServiceTest` |
| Run E2E tests (headless) | `make e2e` |
| Run E2E tests (browser visible) | `make e2e-headed` |
| Lint check | `make lint` |
| Auto-fix lint | `make lint-fix` |
| Compile CSS bundles | `make css` |

## Architecture

**Hexagonal (Ports & Adapters)** — the domain layer has zero framework dependencies.

```
src/main/kotlin/com/kauth/
├── domain/
│   ├── model/       # Pure data classes, value objects
│   ├── port/        # ~27 interface contracts (repository, token, email, etc.)
│   └── service/     # Business logic, returns sealed Result types
├── adapter/
│   ├── web/         # Ktor route handlers
│   ├── persistence/ # Exposed ORM (PostgreSQL)
│   ├── token/       # JWT (RS256) + bcrypt
│   ├── email/       # SMTP
│   └── social/      # Google/GitHub OAuth
├── infrastructure/  # Encryption (AES-256-GCM), rate limiting, TOTP, key management
└── config/          # ServiceGraph (composition root) + EnvironmentConfig
```

- **Application.kt** is the composition root — all dependency wiring happens via `ServiceGraph`.
- **EnvironmentConfig** centralizes env var parsing with fail-fast validation.
- **Flyway migrations** in `src/main/resources/db/migration/` (V1–V24, immutable).

## Key Conventions

- **Sealed Result types** — domain services return `Result<T>` or `AdminResult<T>`, never throw exceptions for business logic errors.
- **Tenant scoping** — all queries must be filtered by `TenantId`. No cross-tenant data leaks.
- **Fakes over mocks** — tests use in-memory `FakeXyz` implementations of ports (in `src/test/kotlin/com/kauth/fakes/`), not MockK stubs.
- **Tests run in-memory** — no Docker, no database, no HTTP required for `make test`.
- **No framework imports in domain** — `domain/model/`, `domain/port/`, `domain/service/` must not import Ktor, Exposed, or any framework.
- **Frontend is server-rendered** — views use `kotlinx.html` DSL (`*View.kt` files). No TypeScript or client-side framework.
- **CSS is build-time only** — LightningCSS compiles 4 bundles (admin, auth, portal-sidenav, portal-tabnav) via `make css`. No runtime Node.js.

## Testing

Two-layer test pyramid:
1. **Domain service tests** (`domain/service/*Test.kt`) — business logic, error paths, no I/O.
2. **Route integration tests** (`adapter/web/**/*Test.kt`) — HTTP concerns using Ktor `testApplication`, auth, sessions, redirects.

Each port interface has a corresponding `Fake` implementation in `src/test/kotlin/com/kauth/fakes/`.

## Code Style

- **ktlint 1.3.1** — max line length 120.
- **EditorConfig**: 4-space indent (Kotlin), 2-space (JSON/YAML), tabs (Makefile).
- `*View.kt` files (kotlinx.html DSL) are exempt from ktlint.
- Wildcard imports are allowed in Exposed/Ktor adapter files.
- Logging: use `call.application.log` in routes. Never log tokens, passwords, or secrets.

## Docker Compose Variants

- `docker-compose.quickstart.yml` — one-command demo (pre-built image + bundled DB)
- `docker/docker-compose.dev.yml` — build from source
- `docker/docker-compose.external-db.yml` — external database
- `docker/docker-compose.prod.yml` — Caddy TLS overlay

## Environment Variables

- **Required**: `KAUTH_BASE_URL`, `DB_USER`, `DB_PASSWORD`
- **Recommended**: `KAUTH_SECRET_KEY` (32+ hex chars for AES-256-GCM + session signing)
- **Optional**: `KAUTH_ENV` (default: development), `DB_URL` (overrides DB_HOST/DB_PORT/DB_NAME), `DB_POOL_MAX_SIZE` (default: 10), `DB_POOL_MIN_IDLE` (default: 2), `KAUTH_ADMIN_BYPASS` (default: false — enables direct password login on admin console)

## ADRs

Architecture Decision Records are in `docs/adr/` — consult these before changing architectural patterns (hexagonal structure, migration strategy, sealed result types, audit logging, secret hashing).
