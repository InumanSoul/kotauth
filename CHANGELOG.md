# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.2.0] - 2026-03-27

### Added

- **Admin Console OAuth Dogfooding** — the admin console now authenticates via OAuth Authorization Code + PKCE through the master tenant, replacing direct password auth. Admin login flows through the same auth pipeline as every other Kotauth consumer, gaining MFA enforcement, session tracking, and token revocation for free
- **Admin role gating** — a `admin` role is provisioned on the master tenant (V28 migration). Only users with this role can access the admin console. Enforced in both OAuth and bypass modes
- **OIDC end-session logout** — admin logout revokes the DB session, clears the cookie, and redirects through the OIDC end-session endpoint with `id_token_hint` for proper RP-initiated logout
- **Break-glass bypass** — `KAUTH_ADMIN_BYPASS=true` environment variable keeps the old direct password login available for recovery scenarios. Defaults to `false`. Startup warning logged when active
- **Account lockout** — per-user failed login attempt counter with configurable threshold (default: 10) and lockout duration (default: 15 min). Disabled by default — admin opt-in per tenant. Admin can unlock users from the admin console. Users receive an email notification with a password reset link when locked
- **`SecurityConfig` extraction** — password policy, MFA policy, and lockout config moved from `tenants` table to dedicated `tenant_security_config` table (V26), following the existing `TenantTheme` and `PortalConfig` pattern
- **Account locked email** — async notification with lockout duration and password reset CTA. Gated by `tenant.isSmtpReady`
- **Password changed email** — async security notification sent on all password change paths (self-service, reset link, admin-initiated). No CTA link to prevent phishing surface
- **`KAUTH_ADMIN_BYPASS`** environment variable — controls whether direct credential login is available on the admin console
- **Admin client auto-provisioning** — `AdminClientProvisioning` ensures the master tenant has a `kotauth-admin` public OAuth client with the correct redirect URI, issuer URL, and branding logo at startup

### Security

- **HMAC-signed admin cookie** — `KOTAUTH_ADMIN` session cookie now uses `SessionTransportTransformerMessageAuthentication` with a dedicated `adminSessionKey` (different derivation prefix from portal)
- **OAuth `state` parameter** — CSRF protection on both admin and portal OAuth flows. Random state embedded in signed PKCE cookie and verified on callback
- **Open redirect prevention** — OIDC end-session endpoint now validates `post_logout_redirect_uri` against the request origin. External URIs are rejected
- **PKCE cookie `Secure` flag** — both admin and portal PKCE cookies now set `secure` based on `baseUrl` scheme
- **Portal security parity** — portal OAuth flow upgraded with `state` CSRF parameter, `secure` cookie flag, and `kotlinx.serialization` JWT parser (replacing fragile regex)
- **Master tenant registration disabled** — V28 sets `registration_enabled = false` on master tenant. Login page hides "Create an account" when registration is off

### Changed

- **Shared OAuth utilities** — `generatePkceVerifier()`, `generatePkceChallenge()`, and `decodeJwtPayload()` extracted from duplicated private functions in AdminRoutes and PortalRoutes to shared `OAuthUtils.kt`. JWT parser upgraded from regex to `kotlinx.serialization`
- **Admin session model** — expanded from `AdminSession(username)` to include `userId`, `tenantId`, `accessToken`, `idToken`, `adminSessionId`. Sessions are backed by real entries in the sessions table
- **Admin session TTL** — reduced from 8 hours to 1 hour to match access token expiry
- **Master tenant defaults** — startup provisioning sets issuer URL from `KAUTH_BASE_URL`, logo from built-in brand asset, sharp border radius. Replaces the V1 placeholder `kauth.example.com`
- **Login page** — hides "Don't have an account? Create one" when `registrationEnabled = false`
- **Brand logo sizing** — `width="180" height="48"` on auth page logos for correct rendering without CSS dependency

### Fixed

- **Locked badge visibility** — user list shows amber "Locked" badge (distinct from gray "Disabled"). Precedence: Disabled > Locked > Active
- **SecurityConfig upsert** — `PostgresTenantRepository.update()` now uses upsert pattern for `tenant_security_config`, fixing settings not being saved for tenants created after V26
- **V28 role scope** — uses lowercase `'tenant'` matching the DB check constraint

### Removed

- **Legacy `kotauth-admin-console`** confidential client — replaced by `kotauth-admin` public PKCE client in V28
- **Legacy tenant policy columns** — V27 drops `password_policy_*` and `mfa_policy` from `tenants` table (data migrated to `tenant_security_config` in V26)

---

## [1.1.5] - 2026-03-26

### Fixed

- **Broken verification email links** — `AuthService.register()` passed an empty `baseUrl` to email verification, producing relative URLs that don't work in email clients. Deleted the duplicate 6-param overload, wired `baseUrl` through the route layer
- **Audit log details always empty** — `PostgresAuditLogRepository.toAuditEvent()` now parses the JSONB `details` column via `kotlinx.serialization.json.Json`. API consumers and admin UI now see actual audit event details (IP changes, session IDs, etc.)
- **OAuth context lost on password expired redirect** — password expired redirect during an OAuth flow now preserves all OAuth params in the query string
- **CSP violation on admin redirect** — replaced inline JS workspace redirector with server-side cookie (`kotauth_last_ws`) + direct redirect. Replaced inline `onchange` handler with `data-autosubmit` attribute
- **Rate limit keys now tenant-scoped** — changed from `login:$ip` to `login:$ip:$slug` across all 4 rate-limited endpoints. One tenant's traffic no longer affects another's budget
- **Rate limiter memory leak** — `InMemoryRateLimiter` now prunes idle buckets when the map exceeds 1,000 keys
- **`toRole()` N+1 query** — removed per-row composite child query from the role mapper. `RoleGroupService.listRoles()` now batch-fetches all child mappings in one query via `findAllChildMappings()`

### Added

- **Composite database indexes** (V25 migration) — `idx_sessions_tenant_user_active` for session lookups and `idx_audit_tenant_created` for audit log queries. Covers the most frequent query patterns
- **Shared `applicationScope`** — coroutine scope in `ServiceGraph` shared by `WebhookService` and `UserSelfServiceService`. Cancelled on shutdown to allow in-flight work to complete
- **Session cleanup job** — background coroutine runs hourly, purging expired and revoked sessions older than 7 days
- **`sha256Hex` shared utility** — extracted from 5 duplicate private functions into `domain/util/Hashing.kt`

---

## [1.1.4] - 2026-03-26

### Security

- **CVE-2025-55163** — Netty HTTP/2 DDoS vulnerability. Mitigated by constraining `netty-codec-http2` to 4.1.124.Final
- **CVE-2025-24970** — Netty native SSL crash on crafted packet. Mitigated by constraining `netty-handler` to 4.1.124.Final
- **GHSA-72hv-8253-57qq** — Jackson async parser DoS. Mitigated by constraining `jackson-core` to 2.18.6
- **CVE-2025-11226 / CVE-2026-1225** — Logback arbitrary code execution. Fixed by upgrading to 1.5.32
- **CVE-2025-49146** — PostgreSQL JDBC MITM attack. Fixed by upgrading to 42.7.10
- **CSRF protection** — Added `SameSite=Lax` attribute to both `KOTAUTH_ADMIN` and `KOTAUTH_PORTAL` session cookies
- **Content Security Policy** — Added `Content-Security-Policy` header to all responses (`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'`)
- **Thread-safe JWT cache** — Replaced `mutableMapOf` with `ConcurrentHashMap` in `JwtTokenAdapter.algorithmCache` to prevent data race under concurrent token issuance

### Fixed

- **Webhook `X-KotAuth-Event` header** — was incorrectly sending the endpoint URL instead of the event type (e.g., `user.created`). Receivers relying on this header for event routing now get the correct value

### Changed

- **Dependency upgrades** (no breaking changes):
  - Logback 1.4.14 → 1.5.32
  - PostgreSQL JDBC 42.7.3 → 42.7.10
  - Logstash encoder 7.4 → 8.0
  - Exposed 0.50.1 → 0.55.0
  - java-jwt 4.4.0 → 4.5.1
  - MockK 1.13.10 → 1.13.16
  - JUnit Jupiter 5.10.2 → 5.10.5

### Removed

- **`ktor-server-auth-jwt`** dependency — declared but unused (zero imports). All JWT operations use `com.auth0:java-jwt` directly

---

## [1.1.3] - 2026-03-25

### Added

- **HikariCP connection pool** — replaced bare JDBC `DriverManager.getConnection()` (new TCP connection per transaction) with HikariCP 5.1.0 pooled connections. Eliminates 10-20ms of TCP/TLS/auth overhead per DB call. Pool configured with leak detection (4s threshold), connection keepalive, and max lifetime rotation
- **`DB_POOL_MAX_SIZE`** environment variable — configurable maximum pool size (default: 10)
- **`DB_POOL_MIN_IDLE`** environment variable — configurable minimum idle connections (default: 2)
- **Multi-arch Docker images** — publish workflow now builds `linux/amd64` and `linux/arm64` natively in parallel using GitHub's free arm64 runners. No QEMU emulation

### Changed

- **Async email delivery** — verification and password-reset emails are now sent in a background coroutine (`CoroutineScope + SupervisorJob + Dispatchers.IO`), matching the existing async webhook pattern. HTTP responses return immediately instead of blocking on SMTP
- **Admin route intercepts** — extracted ~60 duplicate `findBySlug` + `findAll` calls from 7 admin sub-route files into a single `intercept(ApplicationCallPipeline.Call)` block at the `/{slug}` route level. Workspace and sidebar data resolved once per request via `call.attributes`
- **Auth route intercepts** — extracted ~21 duplicate `findBySlug` calls from 6 auth sub-route files into a single intercept at the `/t/{slug}` route level. Tenant, theme, and workspace name resolved once per request via `AuthTenantContext`

---

## [1.1.2] - 2026-03-25

### Added

- **External database compose file** — `docker/docker-compose.external-db.yml` runs only the Kotauth container, no bundled PostgreSQL. For managed providers (RDS, Supabase, Neon) or any existing PostgreSQL instance
- **"Bring your own database" section** in README
- **`CODE_OF_CONDUCT.md`** — Contributor Covenant for community guidelines

### Changed

- **CONTRIBUTING.md** — replaced `./gradlew` commands with `make` targets throughout; added full Makefile target reference table, typical dev loop, and IDE setup instructions
- **kotauth-docs external database guide** — replaced workaround (override file + `docker compose up app`) with dedicated `docker-compose.external-db.yml`
- **kotauth-docs Docker page** — added external database compose file to the compose file listing, updated production examples to show both bundled and external DB paths
- **`.env.example`** — added reference to `docker-compose.external-db.yml` in the external database section

---

## [1.1.1] - 2026-03-24

### Added

- **Zero-config quickstart** — `docker-compose.quickstart.yml` at repo root for one-command local evaluation with demo data pre-loaded
- **Live demo and docs links** in README header

### Changed

- **README restructured** — "Try it — one command" section leads the quickstart; existing configurable setup moved to second section
- **DB_URL marked optional** in environment variable table — auto-constructed from `DB_HOST`/`DB_PORT`/`DB_NAME` when not set
- **PostgreSQL port no longer exposed** to host in `docker/docker-compose.yml` — only accessible within the Docker network

### Fixed

- Broken link to `docs/IMPLEMENTATION_STATUS.md` in README — now points to `docs/adr/`
- Missing `-o` flags in kotauth-docs quickstart curl commands — files were printed to stdout instead of saved
- Stale Docker image tag examples (`1.0.1` → `1.1.1`) in documentation site
- Incorrect CSS bundle count in docs ("two" → "four": admin, auth, portal-sidenav, portal-tabnav)

---

## [1.1.0] - 2026-03-22

### Changed

- **Split AdminRoutes.kt** (~1831 lines) into 7 focused route files under `adapter/web/admin/`: `AdminSettingsRoutes`, `AdminApplicationRoutes`, `AdminUserRoutes`, `AdminSessionAuditRoutes`, `AdminRbacRoutes`, `AdminApiKeyRoutes`, `AdminWebhookRoutes`. The orchestrator is now ~279 lines
- **Split AuthRoutes.kt** (~1764 lines) into 7 focused route files under `adapter/web/auth/`: `LoginRoutes`, `RegisterRoutes`, `SelfServiceRoutes`, `MfaRoutes`, `SocialLoginRoutes`, `OAuthProtocolRoutes`, `AuthHelpers`. The orchestrator is now ~80 lines
- **Extracted ServiceGraph** — composition root moved from `Application.kt` into `config/ServiceGraph.kt` with `EnvironmentConfig` for fail-fast env validation
- **EncryptionService** converted from `object` singleton to injectable `class` — receives secret key via constructor, no more `System.getenv()` calls
- **RateLimiter** extracted behind `RateLimiterPort` interface in domain layer — routes depend on port, not concrete implementation
- **AuthService.login()** now delegates to `authenticate()` internally — eliminates duplicated validation logic

### Removed

- Legacy admin redirect routes (`/admin/settings`, `/admin/users`, etc.) — V1 has no backward-compat burden

---

## [1.0.3] - 2026-03-19

### Added

- **BEM design system — settings pages**: Rewrote Security Policy, SMTP, Identity Providers, API Keys, and Webhooks pages from legacy CSS to BEM components (`ov-card`, `edit-row`, `toggle-row`, `chip-grid`, `copy-field`, `key-table`)
- **BEM design system — RBAC pages**: Rewrote Roles (list/create/detail) and Groups (list/create/detail) to BEM with `data-table`, `page-header`, `edit-row`, `key-table`, and `empty-state` components
- **`settings.js`** — CSP-safe JavaScript for all admin interactions: clipboard copy (`data-copy`), confirm dialogs (`data-confirm`), chip-grid toggles (`data-chips-all`/`data-chips-none`), scope field toggle (`data-scope-toggle`)
- **`copy.svg`** icon for clipboard copy buttons
- **`btn--icon`** CSS modifier for icon-only square buttons (24×24)
- **`ov-card__section-label--danger`** CSS modifier for danger zone headings
- **`badge--danger`** CSS modifier (red variant) for failed delivery status
- **`copy-field`** CSS component for monospace value + copy button (callback URLs, one-time secrets)
- **Frontend Architecture docs** — added JavaScript (`settings.js`) and htmx patterns sections to `docs/FRONTEND_CSS.md`

### Changed

- **`copyBtn()` rewritten** — replaced inline `onclick` with `data-copy` attribute and SVG icon; now uses `btn btn--ghost btn--icon` base classes
- **Lock icon** — replaced 🔒 emoji with inline SVG (`lock.svg`) on user detail page
- **Copy buttons across all pages** — replaced `⎘` unicode glyph with `copy.svg` icon in Identity Providers, API Keys, and Webhooks copy-field buttons
- **User detail page** — converted Profile, Active Sessions, and Danger Zone from legacy `section`/`section__title` to `ov-card`/`ov-card__section-label`
- **Branding page** — replaced `form-section__label` with `ov-card__section-label`; each section now wrapped in its own `ov-card`
- **ov-card stacking** — increased sibling gap from 12px to 20px
- **`ov-card__section-label`** — now flex row (`justify-content: space-between`) to support action buttons alongside the title
- **`empty-state__icon`** — added `color: var(--color-muted)` and `svg { width: 16px; height: 16px }` for consistent icon rendering
- **Reset password button** — now always visible on user detail page; disabled with tooltip when SMTP is not configured
- **`docs/FRONTEND_CSS.md`** — updated folder structure, file responsibilities table, and component reference to reflect all new BEM components and CSS files

### Fixed

- **Global label bleed** — added explicit `text-transform: none` resets on `.check-row`, `.radio-row`, `.toggle`, and `.scope-chip` to counteract global `label { text-transform: uppercase }` from `form.css`
- **Checkbox/radio sizing** — added `padding: 0; min-width: 14px; min-height: 14px` resets to prevent global `input` padding from inflating checkbox and radio inputs
- **Branding double-spacing** — removed flex gap from `.branding-form` to prevent stacking with ov-card sibling margin
- **Branding double-border** — removed `border` from `.preset-group` and `.color-grid` now that they sit inside bordered ov-cards

---

## [1.0.2] - 2026-03-19

### Added

- `docker/docker-compose.prod.yml` — production overlay that adds a Caddy sidecar for automatic Let's Encrypt TLS
- `docker/Caddyfile` — Caddy reverse proxy configuration used by the production compose stack
- `docker/docker-compose.dev.yml` — dedicated compose file for contributors building from source (`make up`)
- `DB_HOST` environment variable — hostname component for constructing the JDBC URL (default: `db`)
- `DB_PORT` environment variable — port component for constructing the JDBC URL (default: `5432`)
- `.env.example` committed to the repository as the canonical environment template
- `docs/guides/production-deployment.md` — full production deployment walkthrough covering Caddy, nginx, Traefik, upgrades, and backup

### Changed

- Docker files reorganised into a `docker/` subdirectory (`docker-compose.yml`, `docker-compose.dev.yml`, `docker-compose.prod.yml`, `Caddyfile`)
- `docker/docker-compose.yml` now uses the pre-built GHCR image (`ghcr.io/inumansoul/kotauth:latest`) instead of building from source — no repo clone required to run Kotauth
- `DB_URL` is now an optional override. When not set, the compose stack constructs it from `DB_HOST`, `DB_PORT`, and `DB_NAME`. Setting `DB_URL` directly takes full precedence, enabling external or managed database connections (RDS, Supabase, Neon, etc.) with custom JDBC parameters such as `?sslmode=require`
- `README.md` rewritten: zero-clone quickstart as the primary path, CI/Docker/release/license badges, available image tags table
- Makefile `make up` and related targets updated to reference `docker/docker-compose.dev.yml`

### Fixed

- `DB_URL` in the compose stack was hardcoded and would silently override any `DB_URL` value set in `.env`
- GitHub repository URL placeholder (`your-org/kotauth`) corrected throughout docs and config

---

## [1.0.1] - 2026-03-19

### Added

- Admin console welcome page with live health details in development mode
- New helper utilities for internal route handling

---

## [1.0.0] - 2026-03-17

Initial stable release.

### Added

- **OAuth2 / OIDC provider** — Authorization Code + PKCE, Client Credentials, Refresh Token rotation, token introspection (RFC 7662), token revocation (RFC 7009), OIDC discovery (RFC 8414), JWKS endpoint
- **Multi-tenancy** — fully isolated workspaces with per-workspace RS256 key pairs, user directories, OAuth clients, token policies, and branding; master workspace for platform administration
- **RBAC** — tenant-scoped and client-scoped roles, composite role inheritance with BFS expansion and cycle detection, group hierarchy with nested membership
- **MFA** — TOTP (RFC 6238) with QR code enrollment, 10 recovery codes per user, per-workspace policy (`optional`, `required`, `required_for_admins`)
- **Social login** — Google and GitHub OAuth2 with automatic account linking by email address
- **User self-service portal** — email verification, password reset (no email enumeration), profile editing, password change, session listing and revocation, MFA enrollment
- **Admin console** — workspace settings, user management, application management, API key management, audit log viewer, webhook management
- **Webhooks** — HMAC-SHA256 signed delivery for 8 event types with exponential backoff retry (immediate, 5 min, 30 min)
- **REST API v1** — 30+ endpoints with API key authentication; OpenAPI 3.1 spec and Swagger UI at `/t/{slug}/api/v1/docs`
- **Audit logging** — 30+ immutable event types, append-only, queryable via API and admin console
- **Security baseline** — bcrypt passwords, AES-256-GCM SMTP credential encryption, SHA-256 refresh token and API key hashing, IP-based rate limiting, security response headers, CSRF protection, startup validation for production mode
- **Flyway migrations** — versioned schema management, runs automatically on startup
- **Multi-stage Docker build** — ~120 MB runtime image (`eclipse-temurin:17-jre`), published to `ghcr.io/inumansoul/kotauth`
- **CI/CD** — GitHub Actions pipelines for lint, test, and Docker image publishing on version tags
- **Integration guide** — React SPA with TanStack Router and `oidc-client-ts`

---

[Unreleased]: https://github.com/inumansoul/kotauth/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/inumansoul/kotauth/compare/v1.1.5...v1.2.0
[1.1.5]: https://github.com/inumansoul/kotauth/compare/v1.1.4...v1.1.5
[1.1.4]: https://github.com/inumansoul/kotauth/compare/v1.1.3...v1.1.4
[1.1.3]: https://github.com/inumansoul/kotauth/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/inumansoul/kotauth/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/inumansoul/kotauth/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/inumansoul/kotauth/compare/v1.0.3...v1.1.0
[1.0.3]: https://github.com/inumansoul/kotauth/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/inumansoul/kotauth/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/inumansoul/kotauth/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/inumansoul/kotauth/releases/tag/v1.0.0
