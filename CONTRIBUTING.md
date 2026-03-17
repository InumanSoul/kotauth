# Contributing to Kotauth

Thanks for considering a contribution. This document covers how to get the project running locally, the conventions the codebase follows, and the process for submitting changes.

---

## Setting Up Your Development Environment

**Prerequisites:**
- JDK 17+ (we recommend [Eclipse Temurin](https://adoptium.net/))
- Docker and Docker Compose
- Kotlin-aware IDE — [IntelliJ IDEA](https://www.jetbrains.com/idea/) Community or Ultimate

**Clone and start:**

```bash
git clone https://github.com/your-org/kotauth.git
cd kotauth

# Start the PostgreSQL dependency only
docker compose up db -d

# Copy the development env file
cp .env.example .env

# Run the app from your IDE or with Gradle
./gradlew run
```

The application starts on `http://localhost:8080`. Database migrations run automatically on startup via Flyway.

Alternatively, run everything in Docker:

```bash
docker compose up
```

---

## Project Structure

```
src/main/kotlin/com/kauth/
  Application.kt          — Entry point and composition root
  domain/
    model/                — Pure data classes (no framework imports)
    port/                 — Interface contracts (Repository, EmailPort, etc.)
    service/              — Business logic (AuthService, OAuthService, etc.)
  adapter/
    web/                  — Ktor HTTP route handlers
    persistence/          — PostgreSQL adapters (Exposed ORM)
    token/                — JWT adapter, password hasher
    email/                — SMTP adapter
    social/               — Google / GitHub OAuth adapters
  infrastructure/         — Cross-cutting: encryption, rate limiting, TOTP, key generation

src/test/kotlin/          — Unit tests and fakes (no database required)
src/main/resources/
  db/migration/           — Flyway SQL migrations (V1–V21)
  openapi/v1.yaml         — OpenAPI 3.1 specification
```

---

## Architecture

Kotauth follows [hexagonal architecture](https://alistair.cockburn.us/hexagonal-architecture/). The key rule is:

**The domain layer has zero dependencies on any framework, database, or HTTP library.**

Domain services (`AuthService`, `OAuthService`, etc.) depend only on port interfaces — never on Ktor, Exposed, or any other adapter. This makes them testable in complete isolation.

When adding a new feature:

1. Model the domain entity in `domain/model/`
2. Define the port interface in `domain/port/`
3. Write the business logic in `domain/service/`
4. Implement the adapter in `adapter/persistence/` (database), `adapter/web/` (HTTP), etc.
5. Wire everything together in `Application.kt`

---

## Running Tests

```bash
./gradlew test
```

The test suite uses in-memory fakes (not mocks) for all adapters — no database required. New domain logic should have unit tests using the fake adapters in `src/test/kotlin/fakes/`.

When adding a new repository, add a corresponding fake in `src/test/kotlin/fakes/` that implements the port interface with an in-memory `MutableMap`.

---

## Database Migrations

All schema changes go through Flyway. Migration files live in `src/main/resources/db/migration/`.

Naming convention: `V{next_number}__{Description_with_underscores}.sql`

Example: `V22__Add_magic_link_tokens.sql`

Rules:
- Never edit an existing migration — Flyway will reject a modified checksum
- Write idempotent migrations where possible (`CREATE TABLE IF NOT EXISTS`, etc.)
- Each migration should do one logical thing

---

## Code Conventions

**Kotlin style:** Follow standard Kotlin idioms. The existing codebase is a good reference.

**Error handling:** Domain services return sealed `Result<T>` or `AdminResult<T>` types — not exceptions. Route handlers pattern-match the result and map to HTTP responses. Do not throw exceptions for expected business-rule failures.

**No nulls in the domain:** Use Kotlin's type system. If a value might be absent, use `T?` and handle the null case explicitly. Avoid `!!`.

**Logging:** Use the Ktor application logger (`call.application.log` in routes). Sensitive values (tokens, passwords, secrets) must never appear in log output.

**HTTP routes:** Follow the existing pattern — one `fun Route.xxxRoutes(...)` extension function per route module, injected dependencies as function parameters.

---

## Submitting a Pull Request

1. Fork the repository and create a branch from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes. Keep commits focused — one logical change per commit.

3. Add or update tests. New domain logic should have unit test coverage. New HTTP endpoints should at minimum have a happy-path integration test.

4. Run the full test suite:
   ```bash
   ./gradlew test
   ```

5. Open a pull request against `main`. Include:
   - A clear description of what the change does and why
   - Any relevant migration files
   - If you're changing auth flows or security behavior, note that explicitly — it gets closer review

6. If your change adds a new architectural decision, document it as an ADR in `docs/IMPLEMENTATION_STATUS.md` under the ADR section.

---

## Reporting Bugs

Open a GitHub issue with:
- Steps to reproduce
- Expected vs actual behavior
- Kotauth version or commit hash
- Relevant logs (sanitize any credentials or tokens)

For security vulnerabilities, please do not open a public issue. Email the maintainers directly.

---

## What's Worth Contributing

Good areas to contribute:

- **Admin HTML UI** for role and group management — the REST API is complete, the HTML pages aren't built yet
- **CI/CD pipeline** — GitHub Actions for test + build on PR
- **Integration guides** — any OIDC-compatible framework or language
- **Test coverage** for admin, portal, and API routes
- **Bug fixes** — always welcome

Out of scope for V1 (don't start these without discussion first):

- LDAP / SAML federation
- WebAuthn / Passkeys
- Multi-region / distributed PostgreSQL
- Prometheus metrics

---

## License

By contributing, you agree that your contributions will be licensed under the project's [MIT License](LICENSE).
