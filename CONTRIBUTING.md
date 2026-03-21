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
git clone https://github.com/inumansoul/kotauth.git
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

## Testing Philosophy and Conventions

```bash
./gradlew test
```

The test suite runs entirely in-memory — no database, no network, no Docker. All tests should pass on a fresh clone with just `./gradlew test`.

### Fakes over mocks

Every port interface has a corresponding in-memory fake in `src/test/kotlin/com/kauth/fakes/`. Fakes are deterministic `MutableMap`-backed implementations that behave like the real adapter minus the database. Use fakes for all dependencies the test exercises directly. Reserve `mockk(relaxed = true)` only for dependencies that are not the focus of the test and whose behavior is irrelevant (e.g., `KeyProvisioningService` when testing login).

When adding a new repository port, add a matching fake with the same name prefixed with `Fake` (e.g., `UserRepository` → `FakeUserRepository`). The fake must implement `clear()` for `@BeforeTest` resets.

### Test layers

The test suite follows a two-layer pyramid.

**Domain service unit tests** (`domain/service/*Test.kt`) — test business logic in isolation. Each service is constructed with fakes for all its ports. These validate rules, error paths, and edge cases. This is where the bulk of the logic coverage lives.

**Route integration tests** (`adapter/web/**/*Test.kt`) — use Ktor's `testApplication` with real routing wired to fakes. These validate HTTP concerns that the domain layer cannot see: authentication guards, session cookies, scope enforcement, DTO serialization, status codes, redirect behavior, and cross-tenant isolation. They do not duplicate the domain logic tests — they test the wiring.

### Writing a route integration test

Follow the established pattern:

1. Construct fakes and services as class-level properties.
2. In `@BeforeTest`, call `clear()` on every fake and seed the minimum fixtures (tenant, user, API key or session).
3. Write a private `installTestApp()` extension on `Application` that installs `ContentNegotiation { json() }`, any auth/session plugins, and the route module under test.
4. Each test calls `testApplication { application { installTestApp() } ... }`.
5. For admin routes behind a session guard, use `createClient { install(HttpCookies) }` and call a `login()` helper before hitting protected endpoints.
6. For API routes behind bearer auth, use `bearerAuth(rawApiKey)` where `rawApiKey` comes from `ApiKeyService.create()` in `@BeforeTest`.
7. When testing redirects, use `createClient { followRedirects = false }` and assert on `HttpStatusCode.Found` + the `Location` header.

### Known serialization gotcha

Ktor's `ContentNegotiation { json() }` uses kotlinx.serialization under the hood. Responding with `mapOf("key" to someBoolean)` produces `Map<String, Any>`, and kotlinx.serialization cannot serialize the polymorphic `Any` type. Always use `buildJsonObject { put("key", value) }` instead of `mapOf(...)` when the response contains mixed types (String, Boolean, Long). The OIDC discovery endpoint is the reference implementation of this pattern.

### Test file organization

Group tests by route module. One test class per route file is the default. Split into separate files when a single test class exceeds ~40 tests or when distinct concerns emerge (e.g., `AdminRoutesTest` for auth guard vs. `AdminSettingsTest` for workspace settings vs. `AdminApiKeysTest` for key management). Shared wiring duplication across sibling test classes is acceptable — prefer locality over DRY in tests.

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
