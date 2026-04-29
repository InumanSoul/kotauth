# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.8.0] - 2026-04-29

### Added

- **Optional Redis sidecar for distributed rate limiting and sessions** — set `KAUTH_REDIS_URL` to back the four limiter buckets (login, register, token, mfa) and the session store with a single shared Lettuce connection. Rate limiting uses a sliding-window Lua script; sessions use per-record TTL plus per-user / per-tenant ZSETs scored by `createdAt`. Without `KAUTH_REDIS_URL`, `InMemoryRateLimiter` and `PostgresSessionRepository` keep working unchanged. Multi-replica deployments now apply the configured rate limit across the fleet (instead of `limit × replica count`) and share session state without sticky-session pinning. See [ADR-12](docs/adr/ADR-12-redis-sidecar.md) and [REDIS.md](docs/REDIS.md)
- **Fail-closed runtime + fail-fast startup** — when Redis is configured and unreachable at startup, the server prints a `FATAL` banner and exits. When Redis is configured and a command throws at runtime, `RedisRateLimiter.isAllowed` rejects the request rather than silently falling back to per-replica state. Both behaviors are deliberate: a "fail open" rate limiter triggers exactly when the operator is least able to investigate
- **Six new Redis-related env vars** — `KAUTH_REDIS_URL`, `KAUTH_REDIS_USERNAME`, `KAUTH_REDIS_PASSWORD` (credentials split per the existing `DB_URL`/`DB_USER`/`DB_PASSWORD` pattern), plus three timing knobs `KAUTH_REDIS_TIMEOUT_MS` (250), `KAUTH_REDIS_COMMAND_TIMEOUT_MS` (100), `KAUTH_REDIS_STARTUP_PROBE_TIMEOUT_MS` (2000). Documented in [ENV_REFERENCE.md](docs/ENV_REFERENCE.md)
- **`redis:7-alpine` service in `docker-compose.dev.yml`** — bundled for local development with persistence disabled (rate-limit buckets and sessions are ephemeral by design). Healthcheck wired so `app` waits for Redis before starting
- **Testcontainers-backed integration tests** — `RedisRateLimiterIntegrationTest` (8 cases, including a runtime fail-closed assertion that pauses the container) and `RedisSessionRepositoryIntegrationTest` (13 cases, covering save/find/revoke, cross-tenant isolation, orphan pruning of TTL'd records, and past-expiry TTL handling). Both tagged `@Tag("redis")` and excluded from the default `make test` suite. Run via `make test-redis`; the Makefile target auto-detects the active Docker context (Docker Desktop, OrbStack, Colima) and forwards `DOCKER_HOST` + an explicit `api.version` JVM property so non-Docker-Desktop runtimes work without manual configuration
- **`SessionCodec` round-trip tests** — three unit tests for kotlinx.serialization-based JSON encoding of `Session` (regular, revoked, client_credentials variants). Run as part of the default `make test`

### Changed

- **`RateLimiterPort` and `SessionRepository` selection in `ServiceGraph`** — both branch on `config.redisEnabled` to construct the Redis-backed adapter when `KAUTH_REDIS_URL` is set, or the existing in-memory / PostgreSQL adapter otherwise. Routes and domain services are unchanged; both port interfaces are unchanged
- **`docs/RATE_LIMITING.md` updated** — adds the Redis implementation section (algorithm, fail-closed contract, key format) and removes the "planned for future release" note that referenced this work

---

## [1.7.2] - 2026-04-28

### Added

- **i18n via volume-mounted JSON bundles** — non-English locales are opt-in: drop a `<locale>.json` file into the directory pointed at by `KAUTH_I18N_BUNDLE_DIR`, restart, done. Each bundle is a flat key→string map keyed by `EnglishStrings` field names (e.g. `"PASSWORD": "Contraseña"`). `{0}`, `{1}` placeholders are substituted at render time. English remains baked into the JAR — vanilla, air-gapped, and quickstart installs work unchanged with zero configuration. An `en.json` in the bundle directory is **ignored with a warn log** (the JAR is authoritative). Malformed JSON or non-string values cause that one file to be skipped; other locales still load. See [ADR-11](docs/adr/ADR-11-i18n-volume-mounted-bundles.md)
- **`TranslationPort` domain interface + two adapters** — `EnglishOnlyTranslation` (default, sourced from `EnglishStrings.byKey` via reflection) and `BundleTranslation` (opt-in, JSON-backed). `ServiceGraph` selects between them based on whether `KAUTH_I18N_BUNDLE_DIR` is set
- **`ViewContext` data class** — bundles `theme`, `workspaceName`, `locale`, and a `translator` into a single per-request context. Views call `ctx.t("KEY", arg)` instead of threading locale and translator through every signature. Eight `*View.kt` page functions migrated as part of v1.7.2 (see Changed below)
- **Per-request locale resolution** — `Accept-Language` header > tenant `TenantTheme.defaultLocale` > `"en"`. No user-level override or `?lang=` query param in this release; resolution is deterministic from headers + tenant config
- **Tenant default-locale dropdown on the admin Branding page** — only locales currently loaded by `BundleTranslation` appear in the select. "Auto-detect (browser Accept-Language)" is the default option. Submissions referencing an unloaded locale are silently dropped at the route handler before reaching `AdminService.updateTheme`
- **`default_locale` column on `workspace_theme`** — V37 migration, `VARCHAR(10) NULLABLE`. Stored on `TenantTheme` because operators already conceptualize locale under "branding"
- **`KAUTH_I18N_BUNDLE_DIR` env var** — parsed in `EnvironmentConfig`. Unset = English-only (the default). Set to a directory = bundle loader scans it once at startup
- **`docs/i18n/es.json` sample bundle** — Spanish translations covering the five auth pages migrated in this release. Drop into your bundle dir to enable Spanish on `loginPage`, `forgotPasswordPage`, `resetPasswordPage`, `acceptInvitePage`, `mfaChallengePage`. Pages not yet migrated render in English regardless of the active locale
- **`docs/i18n/README.md`** — operator guide for translation bundles: how loading works, locale resolution per request, how to add a new language, what's covered in v1.7.2 and what isn't

### Changed

- **Five auth view pages migrated to `ctx.t()`** — `AuthView.loginPage`, `forgotPasswordPage`, `resetPasswordPage`, `acceptInvitePage`, `mfaChallengePage`. ~150 hardcoded English strings replaced with translation lookups against `EnglishStrings` keys. Six pages remain hardcoded English (`registerPage`, `magicLinkPage`, `magicLinkErrorPage`, `forceChangePasswordPage`, `verifyEmailPage`, `socialRegistrationPage`) — status documented at the top of `EnglishStrings.kt` and migrated incrementally on next touch
- **`AdminService.updateTheme` now persists `defaultLocale`** — previously the field on `TenantTheme` was passed in but dropped during sanitization, making the existing data-class field a no-op. Fixed by including `defaultLocale = theme.defaultLocale?.trim()?.lowercase()?.takeIf { it.isNotBlank() }` in the sanitized theme before upsert
- **`PostgresThemeRepository` and `PostgresTenantRepository` read/write `default_locale`** — wiring through the new column on both upsert and tenant-load paths

### Documentation

- **ADR-11 — i18n via volume-mounted JSON bundles.** Captures English-default constraint, `TranslationPort` shape, bundle loading semantics, locale resolution priority, the `ViewContext` migration pattern, alternatives considered (sidecar, properties files, DB-backed translations) and the v1.7.2 known limitations (no pluralization, six pages still hardcoded, no end-user locale switcher, no hot reload)

### Limitations (v1.7.2)

- **Six auth pages still hardcoded English.** `registerPage`, `magicLinkPage`, `magicLinkErrorPage`, `forceChangePasswordPage`, `verifyEmailPage`, `socialRegistrationPage` will render in English on a Spanish-default tenant until they are migrated. See `EnglishStrings.kt` header comment for the running checklist
- **Bundles are loaded once at startup.** Editing `es.json` requires a process restart — i18n is operator config, not user content
- **No `?lang=` override or end-user locale switcher.** Resolution is deterministic from `Accept-Language` + tenant default; a user on a French laptop hitting a Spanish-default tenant cannot pick English without changing their browser
- **No pluralization.** `{0}` substitution is enough for the keys currently in `EnglishStrings`; ICU MessageFormat is a future `TranslationPort` adapter swap if pluralization becomes a real need

---

## [1.7.1] - 2026-04-28

### Fixed

- **Magic-link token-burn race on cross-device tap.** In v1.7.0, `GET /t/{slug}/magic-link/consume` called `consumeMagicLink(token)` **before** checking the `KOTAUTH_AUTH_CONTEXT` cookie. A user who requested the link on their laptop and tapped it on their phone (the modal mobile-email case — iOS opens links in system Safari, not the originating in-app browser) saw the friendly "open this link in the same browser" error, but the token had already been marked consumed. The user's same-device retry on their laptop then failed with "this link has already been used" — turning the friendly error into a hostile dead end. The fix in `MagicLinkRoutes.kt` reorders the consume route to check `getAuthContext` first; if the cookie is absent, the route renders the cross-device error **without** touching the token, leaving it valid for the same-device retry. Caught during cross-device priority analysis (deferred to v1.8.x — see [ADR-10](docs/adr/ADR-10-magic-link-passwordless-signin.md))

### Added

- **`MagicLinkRoutesTest.kt`** — 5 HTTP integration tests covering the regression: cross-device tap preserves the token, same-device flow consumes correctly, the canonical "tap on phone, then retry on laptop" sequence succeeds on the same token, feature toggle off behavior, and `POST /magic-link/send` enumeration safety (always redirects to `?sent=true` regardless of email known/unknown)

### Documentation

- **ADR-04 audit closed.** Re-audit of `AdminRbacRoutes.kt` confirmed all mutations correctly route through `RoleGroupService`/`AdminService`. Three remaining direct repository calls are reads (role-create dropdown population, user-search autocomplete) and are deliberately out of scope per ADR-04, which scopes to **write operations** only. No code change needed; memory note updated to reflect closed status

---

## [1.7.0] - 2026-04-28

### Added

- **Magic-link passwordless sign-in** — emailed one-time link as an alternative to password entry, opt-in per tenant via the new "Passwordless Sign-in" toggle on the Security Policy page (`tenant_security_config.magic_link_enabled`, default `FALSE`). The user clicks "Sign in with an email link instead" on the login page, enters their email, receives a 15-minute single-use link; clicking it from the same browser completes the OAuth authorization-code flow exactly as a password login would. MFA is still enforced on the way through — magic link verifies email possession, not the second factor. Designed for SaaS integrators who want a low-friction sign-in path for users who already trust their email account
- **`MAGIC_LINK` added to `TokenPurpose` enum** — shares the `password_reset_tokens` table with `PASSWORD_RESET`, `INVITE`, and `TEMP_PASSWORD`. Cross-purpose token use is rejected at the service layer (a `PASSWORD_RESET` token fed into `consumeMagicLink` returns `TokenInvalid` and does not consume the token). Issuing a new magic link supersedes any prior unconsumed magic link for the same user — only one active link at a time
- **`magic_link_enabled` column on `tenant_security_config`** — V36 migration, defaults to `FALSE`. Opt-in per tenant
- **`MAGIC_LINK_REQUESTED` and `MAGIC_LINK_CONSUMED` audit events** — request audit captures the IP and tenant; consume audit captures user id and tenant. Raw token never appears in the audit log
- **`SmtpEmailAdapter.sendMagicLinkEmail`** — themed HTML + plain-text variants. Subject line and copy do not mention "magic link" by name (avoid leaking feature state via subject)
- **`AuthHelpers.completeAuthorizationCodeFlow` shared helper** — extracted from `OAuthProtocolRoutes` and `MfaRoutes` so the magic-link consume route can resume the authorization-code flow with the same exit semantics (issues code, clears the auth-context cookie, redirects to `redirect_uri?code=…&state=…`). Removes ~40 lines of duplication; the three callers now share one implementation
- **17 new domain-service tests** in `MagicLinkTest.kt` — initiate happy path with token TTL ≈15 min; prior-token deletion; user-enumeration silent paths (unknown tenant / feature off / SMTP off / unknown user / disabled user); consume happy path (returns user, marks token consumed, sets `email_verified = true`, clears `SET_PASSWORD`); single-use enforcement; rejects unknown / wrong-purpose / expired / disabled-user / `CHANGE_PASSWORD`-required tokens. **791 tests total, 0 failures**

### Changed

- **`UserSelfServiceService` gained `initiateMagicLink(email, tenantSlug, baseUrl, ipAddress)` and `consumeMagicLink(rawToken)`** — both surface as `SelfServiceResult`. `initiateMagicLink` always returns `Success` regardless of branch (user-enumeration protection); silent no-ops cover unknown tenant, disabled feature, missing SMTP, unknown email, disabled user. `consumeMagicLink` marks the user `email_verified = true`, clears `SET_PASSWORD` from `required_actions`, and rejects when `CHANGE_PASSWORD` is required (forced password change is not bypassable via magic link)
- **`AdminService.updateWorkspaceSettings`** gained `magicLinkEnabled: Boolean = false` parameter
- **`SecurityConfig`** gained `magicLinkEnabled: Boolean = false` field; persisted via `TenantSecurityConfigTable`/`PostgresTenantRepository`
- **`EmailPort`** gained `sendMagicLinkEmail(to, toName, magicLinkUrl, workspaceName, tenant)` — consistent shape with the other transactional senders

### Security

- **User-enumeration protection on `POST /magic-link/send`** — same redirect to `?sent=true`, same response page, same status code regardless of whether the email exists, the tenant has the feature enabled, SMTP is configured, or the user is disabled. Rate-limit hits also return the same response — no timing or status-code oracle
- **15-minute token TTL, single-use, SHA-256 hash at rest** — only the hash is persisted in `password_reset_tokens`. Raw token only exists in the link sent to the user
- **`CHANGE_PASSWORD` required action blocks consumption** — a user with a forced password change still has to complete that change via the standard force-change flow. Magic link is not a back door around `CHANGE_PASSWORD`
- **MFA invariant preserved** — after `consumeMagicLink` succeeds, the flow re-enters the standard authorization-code path through `completeAuthorizationCodeFlow`, which routes through MFA when the user has it enrolled. Magic link is not an MFA bypass
- **Same-device cookie binding** — consumption requires the `KOTAUTH_AUTH_CONTEXT` cookie set by `/authorize`. Cross-device consumption is rejected with a friendly error rather than silently authenticating the user without their requesting OAuth client context. `POST /magic-link/send` refreshes the cookie on each request so the user gets a full 5-minute window from request, not from the original `/authorize`
- **Rate limit keyed by IP + tenant slug, not email** — rate-limiting by email would create an enumeration oracle (different rate-limit behavior for known vs. unknown emails). Acceptable trade-off because `initiateMagicLink` is enumeration-safe regardless

### Limitations (v1.7.0)

- **Cross-device consumption is not supported.** Click on phone after requesting on laptop → friendly error. v1.7.1 plans to lift this by creating a `PortalSession` directly on consume when no OAuth context is present
- **No standalone magic-link entry point.** Magic links only bootstrap from inside an `/authorize` request — there is no `/login` page that lets a user request a link without an OAuth client. Same v1.7.1 scope as cross-device consume
- **Email is the second-strongest factor we have, not the strongest.** Compromised inbox = compromised account when magic links are enabled. Tenants in regulated environments should leave the toggle off and rely on password + MFA. The toggle copy on the Security Policy page makes this trade-off explicit

### Documentation

- **ADR-10 — Magic-link passwordless sign-in.** Captures TTL/single-use rationale, MFA invariant, required-action interaction, same-device cookie binding, password_reset_tokens table reuse, rate-limiting by IP not email, and the v1.7.0 limitations with their v1.7.1 plan

---

## [1.6.1] - 2026-04-24

### Added

- **Three REST endpoints to programmatically onboard and recover users** — closes the gap for SaaS platforms integrating KotAuth as their auth provider (Oriana, etc.) so they no longer need a human admin in the KotAuth console to invite a team member:
  - `POST /t/{slug}/api/v1/users/invite` — creates a user without a password and emails a 72-hour invite link. The user sets their own password on first login via the existing `/t/{slug}/accept-invite` public page. This is the canonical onboarding path and what SaaS integrators should use by default
  - `POST /t/{slug}/api/v1/users/{id}/send-reset-email` — admin-triggered password reset email. Idempotent
  - `POST /t/{slug}/api/v1/users/{id}/temporary-password` — returns a one-time `/t/{slug}/change-password?token=…` URL in the response body (expires 24h). Useful when SMTP is misconfigured or the ops team prefers delivering the link over a trusted channel instead of email. The raw link is not persisted or logged; treat it as a secret
  - All three are gated by the existing `users:write` scope. Full OpenAPI spec coverage; 12 new integration tests covering happy paths, scope enforcement, tenant isolation, 404s, 409s on duplicates
- **Admin-initiated temporary password (`CHANGE_PASSWORD` required action)** — admin can force a user to change their password on next login. A new "Set Temporary Password" button on the user detail page generates a 24-hour `TEMP_PASSWORD` token; the admin is shown a one-time reveal panel with a `/t/{slug}/change-password?token=…` link to hand to the user over a secure channel. On next login, `AuthService` rejects credentials with `AuthError.PasswordChangeRequired` (**after** password verification, so the flag is not observable to attackers). New `ForceChangePasswordRoutes` mirrors the invite-accept flow: token validated, password policy enforced, history check applied, all active sessions revoked on success. Supports the admin flow the spec predicted: "user is stuck, admin hands them a temporary link" and "after a security incident, rotate affected users"
- **HIBP (Have I Been Pwned) breach password detection** — tenant-level opt-in toggle on the Security Policy settings page. New `BreachedPasswordPort` in the domain; `HibpBreachedPasswordAdapter` in infrastructure uses the k-Anonymity range API (only the first 5 hex chars of the SHA-1 hash leave the process), requests `Add-Padding: true` to neutralize response-size side channels. 60-second per-prefix cache — caching by prefix, not by full hash, avoids a timing oracle on repeat checks. Fail-open: any network error, non-200, or timeout allows the password through with a WARN log (external outage must not block registrations). Wired into `PostgresPasswordPolicyAdapter.validate()` behind `tenant.securityConfig.hibpCheckEnabled`. Error message is neutral ("This password has appeared in a data breach") — no mention of HIBP or the provider by name to avoid leaking the check mechanism
- **`TEMP_PASSWORD` added to `TokenPurpose` enum** — shares the `password_reset_tokens` table with `PASSWORD_RESET` and `INVITE`, differentiated by the `purpose` column. Cross-purpose token usage is rejected at the service layer (a `PASSWORD_RESET` token fed into the force-change endpoint returns `TokenInvalid` and does not consume the token)
- **`ADMIN_FORCED_PASSWORD_CHANGE` audit event** — records admin-triggered rotations with username in details. Raw token never appears in the audit log
- **`hibp_check_enabled` column on `tenant_security_config`** — V35 migration, defaults to `FALSE`. Opt-in per tenant
- **`FakeBreachedPasswordPort`** test double — deterministic breach-set control and `simulateError` for fail-open coverage
- **23 new tests** — `ForcedPasswordChangeTest` (11: token lifecycle, purpose guard, password policy integration, session revocation, single-use enforcement), `AuthServiceTest` CHANGE_PASSWORD guard (4: check-after-verify ordering, audit event, clear-flag success path), `PasswordPolicyHibpIntegrationTest` (6: toggle on/off, breach detection, fail-open, neutral error message, length check runs first), `HibpBreachedPasswordAdapterTest` (3 smoke: empty input short-circuit, fail-open on unreachable upstream, cache TTL contract)

### Changed

- **`UserSelfServiceService` gained `initiateForcedPasswordChange(user)` and `confirmForcedPasswordChange(token, new, confirm)`** — deliberately separate from `confirmPasswordReset`/`confirmAcceptInvite` to avoid parameterizing purpose-guarded branches. 24-hour token expiry, revokes all sessions on success, records `PASSWORD_RESET_COMPLETED` with `method=forced_change` detail
- **`AdminService.setTemporaryPassword(userId, tenantId): AdminResult<String>`** — thin wrapper that delegates token lifecycle to `UserSelfServiceService`, emits the admin audit event, and returns the raw token to the caller for one-time display via `FlashStore`. The raw token is never persisted, logged, or emailed
- **`AdminService.updateWorkspaceSettings`** gained `hibpCheckEnabled: Boolean = false` parameter. The Security Policy page has a new "Breach Detection" toggle card with user-facing copy explaining the k-Anonymity approach and the fail-open guarantee
- **`PostgresPasswordPolicyAdapter` gained an optional `breachedPasswordChecker: BreachedPasswordPort?` constructor dep** — HIBP check runs last (after length, charset, blacklist, history), only when the tenant toggle is on AND a checker is wired. Absence of the checker is silent ("feature off"), not an error
- **`RequiredAction` gained `CHANGE_PASSWORD`** — stored as text[] (V30 schema), no migration needed for the enum value itself. Checked in `AuthService.authenticate` AFTER password verification so the flag cannot be enumerated
- **`AuthError` gained `PasswordChangeRequired`** — web adapter's `toMessage()` returns copy directing the user to the change-password link

### Security

- **`CHANGE_PASSWORD` check order is intentionally after password verify** — unlike `SET_PASSWORD` (which uses a sentinel hash and must short-circuit before verify), `CHANGE_PASSWORD` users have a real password. Checking after verify means invalid-credentials attackers can't distinguish between "valid password, rotation required" and "invalid password" based on response differences
- **Temporary password token is displayed exactly once via `FlashStore`** — the admin route issues a one-shot redirect with a flash key; the next GET to the user-detail page consumes the value and removes it. Refresh shows only the regular page
- **No email delivery of the temporary token** — deliberate. Emailing credentials creates a copy in potentially unencrypted mail storage. Admin hands off over a trusted channel
- **HIBP k-Anonymity is intact** — only 5 hex chars leave the process (~500 possible full hashes per prefix). `Add-Padding: true` neutralizes response-size analysis
- **HIBP cache is per-prefix, not per-hash** — caching by full hash would create a timing oracle revealing which passwords have been checked recently. Per-prefix grants anonymity within the ~500-hash bucket
- **HIBP timeout is 5 seconds** — bounded latency on the password-change hot path. Fail-open means a slow HIBP response never blocks users for more than 5 seconds
- **User-facing error message is neutral** — "This password has appeared in a data breach. Please choose a different password." Does not reveal that a check was performed against HIBP specifically, does not mention "pwned" or the provider name
- **Fail-open on external dependency failure** — HIBP API outages must not break registrations. WARN logs surface the failures for operators without blocking users

### Infrastructure

- **`HibpBreachedPasswordAdapter` uses JDK `HttpClient`** — zero-dependency choice. One GET with one header is trivial; Ktor's async client would add `ktor-client-cio` to the fat JAR and introduce an extra version to track. Pluggable clock + timeout + base URL for deterministic testing

---

## [1.6.0-rc1] - 2026-04-24

### Added

- **Custom user attributes + JWT claim mapping** — per-user string key/value metadata that admins (or a billing integration) can project into issued JWTs with configurable claim names. Inspired by Keycloak's "User Attributes + Protocol Mappers" and Auth0's `app_metadata`, but deliberately simpler: declarative string-to-string projection only, no scripting, no conditional logic. Enables use cases like `custom:plan = pro`, `custom:trial_ends = 2026-05-21`, `custom:sifen_env = production` flowing through the access/id tokens SPAs and APIs consume. Attribute changes propagate on the next token issuance — worst-case staleness bounded by the 60-second mapper cache TTL
- **`user_attributes` table** (V33 migration) — per-user key/value with cascade-on-user-delete, `PRIMARY KEY (user_id, key)`, max 64-char key, max 1024-char value (DB `CHECK` constraint + service-layer validation). `idx_user_attributes_tenant_user` covers the hot "all attributes for user X" lookup on token issuance
- **`tenant_claim_mappers` table** (V34 migration) — tenant-level mapping `(tenant_id, attribute_key) → (claim_name, include_in_access, include_in_id)`. `UNIQUE INDEX (tenant_id, claim_name)` enforces one attribute per claim name within a tenant (race-safe defense-in-depth alongside service validation)
- **`UserAttribute` + `TenantClaimMapper` domain models** with `MAX_KEY_LENGTH = 64`, `MAX_VALUE_LENGTH = 1024`, `MAX_CLAIM_NAME_LENGTH = 128`, `MAX_MAPPERS_PER_TENANT = 20` (soft cap preventing JWT bloat)
- **`UserAttributeRepository` + `TenantClaimMapperRepository` ports** — hexagonal separation; Postgres adapters backed by Exposed
- **`UserAttributeService` + `ClaimMapperService`** domain services with sealed `AttributeResult<T>` type (`Success`, `NotFound`, `ValidationError`, `ReservedClaimName`, `DuplicateClaimName`, `LimitReached`). Reserved OIDC/proprietary claim-name blocklist enforced at write time: 41 names including `sub`, `iss`, `aud`, `exp`, `iat`, `email`, `tenant_id`, `realm_access`, etc. The `projectClaims(mappers, attributes, tokenType)` pure function is the token-issuance hot path — no I/O, safe to call per-token
- **`CachingClaimMapperService`** infrastructure decorator — 60-second TTL on mapper reads, self-invalidating on writes via a `ClaimMapperCacheInvalidator` fun interface. Pluggable clock for deterministic tests. Cross-tenant isolation: invalidating tenant A's cache doesn't disturb tenant B's
- **`TokenPort.issueUserTokens` signature extended** with `customAccessClaims: Map<String, String>` and `customIdClaims: Map<String, String>` (both default `emptyMap()` — preserves byte-identical token shape for callers that don't opt in). `JwtTokenAdapter` stamps each map entry onto the appropriate `JWT.create()` builder after roles, before signing. `OAuthService` projects claims via a lambda `(TenantId) -> List<TenantClaimMapper>` injected from the composition root — keeps infrastructure caching out of the domain
- **Refresh-token flow re-projects claims on every call** — billing systems that flip `plan = trial → pro` see the new claim value in the next refreshed access token, not at the next full login
- **REST API — 6 endpoints** under `/t/{slug}/api/v1/`: `GET /users/{userId}/attributes`, `PUT/DELETE /users/{userId}/attributes/{key}`, `GET /claim-mappers`, `PUT/DELETE /claim-mappers/{attributeKey}`. 4 new API scopes: `user_attributes:read`, `user_attributes:write`, `claim_mappers:read`, `claim_mappers:write`. Error responses follow RFC 7807 (`application/problem+json`): reserved claim → 400, validation → 422, duplicate claim name → 409, tenant cap → 409
- **Admin UI — User Attributes section** on the user detail page (between Profile and Active Sessions): table with key/value/edit/delete, "Configure mapping →" link for unmapped keys, `→ custom:claim` badge for mapped keys, delete-confirm copy that dynamically warns about mapper impact
- **Admin UI — Claim Mappers settings page** at `/admin/workspaces/{slug}/settings/claim-mappers` with sidebar link after Webhooks. Table: attribute key, claim name, Access Token Yes/No badge, ID Token Yes/No badge, Delete. Dedicated New/Edit pages with readonly attribute-key field in edit mode, checkbox toggles for `includeInAccess`/`includeInId`, inline hints pointing to reserved-claim list
- **OpenAPI v1 spec updated** — 6 new endpoint definitions, 5 new schemas (`UserAttributesDto`, `UpsertUserAttributeRequest`, `ClaimMapperDto`, `ClaimMappersDto`, `UpsertClaimMapperRequest`), 2 new tags (User Attributes, Claim Mappers), PII-in-JWT warning prominent in the feature description
- **79 new domain/service tests** — `UserAttributeServiceTest` (15), `ClaimMapperServiceTest` (17), `CachingClaimMapperServiceTest` (7), `TokenClaimMappingTest` (12 integration covering full acceptance criteria: attribute+mapper → claim in token, DELETE attribute → claim absent, DELETE mapper → claim absent, zero mappers → byte-identical token, refresh-token re-projection, cross-tenant isolation). 26 new REST API tests (`ApiUserAttributeRoutesTest` + `ApiClaimMapperRoutesTest`). 10 new admin UI tests (`AdminUserAttributesAndClaimMappersTest`)

### Changed

- **`JwtTokenAdapter.issueUserTokens` now takes two extra parameters** (`customAccessClaims`, `customIdClaims`). Both default to `emptyMap()` — existing call sites that do not pass them receive the pre-feature token shape verbatim
- **`OAuthService` constructor** gained optional `userAttributeRepository: UserAttributeRepository? = null` and `claimMappersFor: (TenantId) -> List<TenantClaimMapper> = { _ -> emptyList() }` parameters. Default-null wiring ensures existing `OAuthService(...)` callers in tests continue to compile and produce zero custom claims
- **`adminRoutes()` signature** gained required `userAttributeService` + `claimMapperService` parameters. Existing admin test fixtures updated
- **`ServiceGraph`** wires `UserAttributeService` and `CachingClaimMapperService` and exposes them as fields. The caching decorator registers itself as the `ClaimMapperCacheInvalidator` of its own wrapped `ClaimMapperService` — writes immediately drop the cached mapper list for that tenant

### Security

- **Reserved claim-name blocklist** prevents admins from stomping on standard OIDC and KotAuth-proprietary claims. Attempts return `400 Bad Request` with a specific claim name in the error body, both via API and admin UI
- **Tenant-scoped `UNIQUE INDEX (tenant_id, claim_name)`** on `tenant_claim_mappers` closes a TOCTOU race between service-layer validation and DB insert — two concurrent writes mapping different attribute keys to the same claim name cannot both succeed
- **PII warning** added prominently to OpenAPI spec and embedded in admin UI hints: "Attribute values flow unencrypted into JWTs, which are base64-decodable by anyone in possession of the token."
- **20-mapper soft cap per tenant** prevents JWT-size abuse from admins configuring dozens of claim projections

### Infrastructure

- **Dependency wiring is self-invalidating** — `CachingClaimMapperService` implements `ClaimMapperCacheInvalidator` itself, passes `this` to the wrapped domain `ClaimMapperService` at construction. No mutable lateinit, no circular-reference workarounds

---

## [1.5.8] - 2026-04-22

### Fixed

- **OAuth2 login no longer blocked by `form-action 'self'` CSP directive** — Chromium enforces `form-action` against the entire redirect chain, not just the form's immediate action target. The login `POST /t/{slug}/authorize` would succeed, but the 302 redirect to the SPA's `redirect_uri` (cross-origin by definition) was blocked as a `form-action` violation. Users saw the login screen, filled it in, clicked submit — and nothing happened. Fix: a new `TenantCspPlugin` sets a per-tenant `Content-Security-Policy` header under `/t/{slug}/*` that extends `form-action` with the registered redirect URI origins for the tenant, using the same origin-derivation logic as the CORS plugin (`CorsPort.policyForTenant`). Non-tenant routes (admin, portal, static) keep the strict global `form-action 'self'`. See [ADR-09](docs/adr/ADR-09-tenant-scoped-csp-form-action.md)
- **CSP policy string extracted to `buildCspPolicy(origins)` helper** — the global `DefaultHeaders` config and the tenant plugin now share one source of truth for standard directives (`default-src`, `script-src`, `style-src`, `font-src`, `img-src`, `form-action`), so adding a directive happens in one place
- **Generated URLs now respect `X-Forwarded-*` headers from the reverse proxy** — `install(XForwardedHeaders)` added to the Ktor module, and `ApplicationCall.resolvedBaseUrl()` switched from `request.local` (raw TCP connection, always `http://<container-host>:8080`) to `request.origin` (proxy-aware). Without this, Kotauth running behind a TLS-terminating proxy (OrbStack locally, nginx/Cloudflare/ALB in production) generated `http://` URLs for invite-link emails, password-reset emails, email-verification links, social-login callback URLs, and — when no per-tenant `issuer_url` override is configured — the OIDC discovery document. SPAs that hit the HTTP URL were auto-redirected by the proxy to HTTPS, but the proxy-emitted 301 response carried no CORS headers, so the browser blocked the request mid-chain. `resolvedBaseUrl()` now also omits the port when it's the default for the scheme (`:443` for `https`, `:80` for `http`), producing cleaner URLs. **Operator note:** set `tenants.issuer_url` explicitly in the admin console workspace settings to pin the OIDC issuer to the configured public URL — per the OIDC spec the issuer should be a fixed value, not derived per-request. **Security note:** `XForwardedHeaders` trusts these headers unconditionally; only safe when Kotauth is always behind a proxy that strips client-supplied `X-Forwarded-*` and sets its own (the documented deployment model)

---

## [1.5.7] - 2026-04-21

### Added

- **Multi-tenant CORS policy** — every OIDC endpoint now emits correct `Access-Control-*` headers. Allowed origins are derived automatically from the `client_redirect_uris.uri` of registered, enabled OIDC clients within the tenant: an operator who adds a redirect URI implicitly authorizes that origin for cross-origin SPA traffic to the tenant's token, userinfo, logout, revoke, and introspect endpoints. Discovery (`/.well-known/openid-configuration`) and JWKS (`/protocol/openid-connect/certs`) remain globally readable via `Access-Control-Allow-Origin: *` per OIDC spec. Denied origins produce a structured `WARN cors_denied tenant=X origin=Y method=Z path=P` log line for operator debugging. See [ADR-08](docs/adr/ADR-08-multi-tenant-cors-policy.md)
- **`Send credentials cross-origin` tenant toggle** — opt-in per-tenant flag on the Security Policy page that enables `Access-Control-Allow-Credentials: true` for BFF / cookie-based cross-origin flows. Default off — standard PKCE public clients using Bearer tokens do not need it
- **V32 migration** — `tenant_security_config.cors_allow_credentials BOOLEAN NOT NULL DEFAULT FALSE`
- **`TenantCorsPlugin`** — route-scoped Ktor plugin installed under `/t/{slug}` (auth routes) and `/t/{tenantSlug}/api/v1` (api routes). Handles OPTIONS preflight terminally (204 for allowed origins, 403 for denied) and injects response headers for actual requests. Path-aware: discovery + JWKS get wildcard ACAO; everything else goes through the tenant's origin allowlist
- **In-process origin cache** — `CorsOriginCache` wraps `PostgresCorsAdapter` with a 60-second TTL; mutations on `AdminService` (`updateApplication`, `setApplicationEnabled`, `updateWorkspaceSettings`) and `AdminApplicationRoutes` client-create invalidate the cache entry for the affected tenant so changes propagate immediately

---

## [1.5.6] - 2026-04-21

### Fixed

- **Fat-jar `META-INF/services/**` files are now merged** — the Ktor Gradle plugin sets `duplicatesStrategy = EXCLUDE` on `shadowJar`, which silently dropped duplicate service-provider files before `ServiceFileTransformer` could concatenate them. In Flyway 10+, location-scanner plugins (`classpath:`, `filesystem:`) are registered via `META-INF/services/org.flywaydb.core.extensibility.Plugin`; the `flyway-database-postgresql` copy overwrote the 29-entry `flyway-core` copy, leaving the registered-prefix list empty. Kotauth crashed on boot with `FlywayException: Unknown prefix for location (should be one of ): classpath:db/callback` (note the empty parens). Fix: `com.gradleup.shadow` added as an explicit plugin, `shadowJar` configured with `duplicatesStrategy = INCLUDE` and `mergeServiceFiles()`. Plugin SPI file in the built jar now has 31 entries including `ClasspathLocationHandlerImpl` and `FilesystemLocationHandler`

---

## [1.5.5] - 2026-04-20

### Changed

- **Ktor upgraded to 3.4.2** — from 2.3.12. Major framework upgrade. All 4 `intercept(ApplicationCallPipeline.Call)` blocks converted to `createRouteScopedPlugin`: `AuthTenantPlugin` (onCall), `AdminSessionGuardPlugin` (onCall), `WorkspaceResolverPlugin` (onCall), and `ApiContextPlugin` (on(AuthenticationChecked) — required for post-auth principal access). `@Serializable` added to `AdminSession` and `PortalSession` for Ktor 3 cookie serialization. `autoComplete = false` updated to `autoComplete = "off"` (kotlinx.html API change, 4 sites). `callloging` import typo fixed to `calllogging`
- **Gradle upgraded to 9.4.1** — from 8.5. Configuration cache now fully functional (272ms cached re-runs). `generateVersionProperties` task converted from `doLast` closure to proper `DefaultTask` subclass to eliminate `Project` reference serialization issue. Zero deprecation warnings
- **Flyway upgraded to 12.4.0** — from 11.8.2. Resolves transitive vulnerability. Zero API changes
- **ktlint plugin upgraded to 14.2.0** — from 12.1.1. Required for Gradle 9 compatibility
- **logstash-logback-encoder upgraded to 8.1** — from 8.0. Resolves transitive jackson-core 2.17.2 vulnerability (GHSA-72hv-8253-57qq). Jackson version constraint added to force 2.21.0 across all dependency trees
- **`delay()` calls converted to Duration API** — `delay(3_600_000)` → `delay(1.hours)`, `delay(5 * 60_000)` → `delay(5.minutes)`. Eliminates legacy Long overload warnings
- **CI workflow updated** — removed hardcoded `gradle-version: '8.5'` from all 3 jobs (lint, test, build). CI now uses the project wrapper (`./gradlew`) to match the committed Gradle version
- **Dockerfile updated** — build stage changed from `gradle:8-jdk17` to `eclipse-temurin:17-jdk` with `./gradlew` wrapper. Ensures Docker builds use the same Gradle version as local development

### Removed

- **Netty/Jackson version constraints** — Ktor 3.4.2 transitively brings Netty 4.2.9.Final and Jackson 2.21.0, superseding the 4.1.132/2.18.6 pins that were patching Ktor 2.3.x vulnerabilities
- **`pageHeaderWithTitleRow` function** — unused dead code in AdminComponents.kt

---

## [1.5.4] - 2026-04-10

### Changed

- **Exposed ORM upgraded to 0.61.0** — from 0.55.0. Zero code changes required — clean drop-in. Brings bug fixes and performance improvements. Prepares for the eventual Exposed 1.0 migration in v2.0
- **Flyway upgraded to 11.8.2** — from 9.22.3. Added required `flyway-database-postgresql` artifact (Flyway 10+ split PostgreSQL support into a separate module). Zero code changes to `DatabaseFactory` — the `configure().dataSource().locations().load().migrate()` API is stable across versions

---

## [1.5.3] - 2026-04-10

### Security

- **Netty upgraded to 4.1.132.Final** — patches CVE-2026-33870 (HTTP request smuggling, High severity) and CVE-2026-33871 (HTTP/2 CONTINUATION frame DoS, High severity). Previous pin at 4.1.124.Final was vulnerable to both

### Changed

- **Kotlin upgraded to 2.3.20** — from 1.9.24. Source-compatible, no breaking changes. Enables K2 compiler, aligns with latest JetBrains toolchain. Gradle deprecation warnings persist (source: Ktor 2.3.12 plugin, resolved when migrating to Ktor 3.x in v2.0)
- **Dependency upgrade plan rewritten** — `docs/internal/GRADLE_UPGRADE_PLAN.md` now contains a deep migration impact analysis for the v2.0 framework upgrade: Ktor 3.x (2.5 days, 4 intercept→plugin conversions + 30 mechanical changes), Exposed 1.0 (1.5 days, limit/offset + TransactionManager accessor), Flyway 11 (30 min). Total: ~4.25 days. Migrations are independent and ordered: Flyway → Exposed → Ktor

---

## [1.5.2] - 2026-04-10

### Added

- **Admin-initiated key rotation** — new "Signing Keys" page under Settings where admins can rotate RS256 signing key pairs. The new key becomes the active signer; the old key remains enabled for token verification (served via JWKS) until manually retired. Supports the full key lifecycle: Active → Verification only → Retired
- **`kid` header on all JWTs** — access tokens, id_tokens, and client credentials tokens now include the `kid` (Key ID) header per RFC 7517. Enables correct key selection during verification after rotation
- **`kid`-based token verification** — `decodeAccessToken` reads the JWT `kid` header and resolves the specific signing key for verification. Tokens signed by rotated-away (but still enabled) keys verify correctly. Falls back to the active key for legacy tokens without `kid`
- **`KeyRotationService`** — domain service with `rotate()` and `retireKey()` operations, audit events (`ADMIN_KEY_ROTATED`, `ADMIN_KEY_RETIRED`), and injectable key generation (no infrastructure imports in domain)
- **Key management admin UI** — table showing all keys (active, verification-only, retired) with `createdAt` timestamps, status badges, rotate button with confirmation dialog, retire button for non-active keys
- **`TenantKey.active` flag** — distinguishes the signing key from verification-only keys. Enforced by a partial unique index (at most one active key per tenant). Migration V31 adds the column and backfills existing keys
- **`TenantKey.createdAt`** — mapped from the existing DB column to the domain model, displayed in the key management UI
- **`TenantKeyRepository.findByKeyId()`** — lookup by kid for verification. `rotate()` — atomic two-UPDATE transaction promoting new key and demoting old. `findAllKeys()` — returns all keys including retired
- **`TokenPort.invalidateSigningKeyCache()`** — exposed on the port interface so the domain rotation service can trigger cache eviction without importing the adapter
- **`FakeTenantKeyRepository`** — in-memory test fake for key rotation tests
- **`SecureTokens` utility** — shared `SecureRandom` singleton in `domain/util/` with `randomBytes()`, `randomBase64Url()`, `randomHex()`. Replaces 12 scattered `SecureRandom()` instantiations across 11 files
- **Settings sidebar dividers** — visual grouping of settings items into Workspace (General, Branding, SMTP), Security & Identity (Security policy, Signing Keys, Identity Providers), and Developer Integration (API Keys, Webhooks)
- **23 new tests** — 13 in `KeyRotationServiceTest` (rotate, retire, cross-tenant isolation, multiple rotations) + 10 in `JwtTokenAdapterKeyRotationTest` (kid presence, verification after rotation, retired key rejection, JWKS composition, cross-tenant cache isolation)

### Changed

- **Signing Keys sidebar position** — moved next to "Security policy" (was between API Keys and Webhooks). Security primitives now grouped together
- **Rotate button** — full-size `btn--primary` in page header (was incorrectly `btn--sm`)
- **Revoke All Sessions button** — full-size `btn--warning` in page header (was incorrectly `btn--sm`)
- **Retire confirmation text** — updated to "Active sessions and tokens signed by it will immediately stop working. This cannot be undone."
- **`TOAST_KEY_RETIRED`** — removed JWKS jargon: "Key retired. Tokens signed with this key will no longer be accepted."
- **Retire failure** — route now re-renders page with error message (was silently redirecting with no feedback)
- **`KeyProvisioningService`** — initial key provisioned with `active=true` explicitly
- **Timestamp formatting** — `KeyRotationViews` aligned to shared `toDisplayString()` (was using a separate inline formatter)
- **Fully-qualified `WebhookResult`** in routes and tests replaced with proper imports

### Removed

- **`SecureRandom()` scattered instantiations** — 12 ad-hoc instantiations across 11 files replaced by `SecureTokens` singleton
- **`SecureTokens.nextBytes()`** — unused method removed before shipping
- **`JwtTokenAdapter.invalidateCache()`** — deprecated method removed, replaced by `invalidateSigningKeyCache()`
- **`SelfServiceError.EmailDeliveryFailed`** — dead sealed class variant never referenced
- **Unused `val user` binding** in `confirmAcceptInvite` — findById kept as not-found guard, dropped assignment
- **Redundant fully-qualified references** in `AdminWebhooksTest` — 6 FQ names replaced with imports

---

## [1.5.1] - 2026-04-10

### Added

- **`AdminRouteContext` + `call.adminContext()`** — extracted repeated session/workspace/wsPairs triple from admin route handlers. `AdminUserRoutes` fully migrated (4 page-rendering handlers use context, 8 POST-only handlers keep direct `WorkspaceAttr` access)
- **`Parameters.typedId()`** — reusable typed-ID extraction helper. Replaced 33 `?.toIntOrNull()?.let { XxxId(it) }` patterns across 3 route files (UserId, RoleId, GroupId, ApplicationId, SessionId)
- **`ApplicationCall.resolvedBaseUrl()`** — extracted duplicated base URL construction. Replaced 7 occurrences across 3 files (AdminUserRoutes, SelfServiceRoutes, OAuthProtocolRoutes)
- **`UserRepository.findByIds()`** — batch query (`WHERE id IN (...)`) for hydrating user lists. Single query replaces N+1 per-member lookups
- **`RoleGroupService.getUsersInGroup()` / `getUsersForRole()`** — batch-hydrated user lists via `findByIds`. Route handlers no longer call `userRepository.findById` directly (partial ADR-04 fix)
- **`validatePasswordPolicy()` private helper** — extracted duplicated password policy + history validation from `confirmPasswordReset`, `confirmAcceptInvite`, and `changePassword` into a single 20-line method

### Changed

- **Group detail page** — N+1 query (1 query per member) replaced with single batch query via `getUsersInGroup`
- **Role detail page** — same fix via `getUsersForRole`

### Fixed

- **`resendVerificationEmail` result silently ignored** — route now branches on `AdminResult.Success`/`Failure` and redirects with error toast on failure

---

## [1.5.0] - 2026-04-07

### Added

- **Invite Users** — admins can create users and send an invite email instead of setting a password. The invited user receives a branded email with a link to set their password and activate their account
- **`RequiredAction` enum** — extensible user action model stored as PostgreSQL `text[]`. Ships with `SET_PASSWORD` for invite flow. Designed as foundation for future temporary passwords (`CHANGE_PASSWORD`) and magic links
- **`TokenPurpose` enum** — discriminator on `PasswordResetToken` (`PASSWORD_RESET` / `INVITE`). Cross-purpose token usage is rejected at the service layer — invite tokens cannot be used on the reset endpoint and vice versa
- **Sentinel password hash** — `User.SENTINEL_PASSWORD_HASH = "!"` for users who haven't set a password. The `AuthService.PendingSetup` guard fires before bcrypt verification, preventing wasted CPU and providing an actionable error message
- **Accept-invite page** — `GET/POST /t/{slug}/accept-invite?token=...` branded page with password fields, client-side validation, rate limiting, and success/error states. Uses `TenantTheme` for workspace branding
- **Admin create-user form** — credential setup radio group: "Send invite email" (default when SMTP ready) / "Set password now". Uses existing `radio-row` BEM component. Invite option disabled with description when SMTP not configured
- **Invite pending badge** — amber `"Invite pending"` badge on user detail page for users with `SET_PASSWORD` in `requiredActions`
- **Resend invite** — `POST /users/{id}/resend-invite` route with toast feedback. Generates new token, invalidates old one, sends fresh email. Propagates failure with error toast when SMTP fails
- **Invite email template** — HTML + plaintext with "Set your password" CTA, 72-hour expiry notice, security footer. Subject: "You've been invited to join {Workspace Name}"
- **Audit events** — `USER_INVITE_SENT` (on create + resend) and `USER_INVITE_ACCEPTED` (on successful password set)
- **`TextArrayColumnType`** — custom Exposed column type for PostgreSQL `text[]` arrays, following the `JsonbColumnType` precedent
- **`PasswordResetTokenRepository.deleteByUserAndPurpose()`** — purpose-scoped token deletion prevents invite tokens from being invalidated by password reset flows (and vice versa)
- **`EnglishStrings`** — 16 new constants for all invite-related user-facing text
- **Migration V30** — `required_actions text[]` on users, `purpose varchar(32)` on password_reset_tokens, partial index on active tokens

### Changed

- **`AdminService.createUser`** — accepts `sendInvite: Boolean` and `password: String?` (nullable). When invite mode: stores sentinel hash, sets `requiredActions = [SET_PASSWORD]`, `emailVerified = false`, dispatches invite email. When password mode: existing behavior unchanged
- **`UserSelfServiceService.confirmPasswordReset`** — now rejects tokens with `purpose != PASSWORD_RESET` (cross-purpose guard)
- **`UserSelfServiceService` token deletion** — both existing `deleteByUser` call sites changed to `deleteByUserAndPurpose(..., PASSWORD_RESET)` to avoid silently invalidating invite tokens
- **Login page** — shows actionable message for invited users: "This account has a pending invitation. Check your email for the invite link, or ask your administrator to resend it."

### Fixed

- **Webhook E2E test** — `WebhookEventType.USER_CREATED` interpolated enum name (`USER_CREATED`) instead of `.value` property (`user.created`), causing checkbox selector timeout. Fixed to use `.value`

---

## [1.4.1] - 2026-04-07

### Added

- **Portal — connected social accounts** — new "Connected accounts" section on the self-service portal Profile page. Displays linked social providers (Google, GitHub) with provider icon and email. Empty state shown for password-only users. Uses existing `SocialAccountRepository.findByUserId()` — no migration needed
- **Entity picker component** — reusable `entityPicker()` search-as-you-type component in `AdminComponents.kt`. Replaces native `<select>` dropdowns for assigning users to roles and groups. Debounced htmx search (300ms), absolute-positioned dropdown, keyboard navigation (arrow keys, enter, escape), ARIA combobox pattern, focusout dismiss
- **RBAC assigned users table** — role detail page now shows a data table of currently assigned users (username linked to user detail, email, "Remove" button). Previously only the assignment form was visible
- **RBAC search endpoints** — `GET /roles/{id}/search-users` and `GET /groups/{id}/search-users` return HTML fragments for the entity picker, capped at 20 results. Shared `respondUserSearch()` handler eliminates code duplication
- **User list pagination** — 25 users per page with htmx-enhanced Prev/Next controls. Position-aware subtitle: "Showing 1–25 of 247 users" / "12 results for 'alice'". Search and pagination compose via `?q=alice&page=2`. Page clamped to valid range. Eliminates the old double-query anti-pattern (`listUsers` called twice)
- **Reusable `paginationControls()` component** — extracted to `AdminComponents.kt` with htmx partial-page swaps and URL push. Used by both users list and audit log
- **`UserRepository.countByTenantId()`** — dedicated count method for pagination. `SELECT COUNT(*)` instead of fetching all rows
- **`SessionRepository.countActiveByTenant()`** — dedicated count for sessions display
- **`AdminService.countUsers()`** — thin wrapper for the user count port method
- **`RoleGroupService.getUserIdsForRole()`** — delegating method for fetching assigned user IDs per role
- **Portal sidebar helpers** — extracted `workspaceInitials()` and `portalSignOutButton()` private helpers replacing duplicated code across sidenav and tabnav shell variants
- **`EnglishStrings` additions** — `PORTAL_SIGN_OUT`, `PORTAL_MY_ACCOUNT`, `PORTAL_ACCOUNT`, `CONNECTED_ACCOUNTS_TITLE`, `CONNECTED_ACCOUNTS_SUBTITLE`, `CONNECTED_ACCOUNTS_EMPTY`

### Changed

- **Sessions list capped at 100** — `findActiveByTenant` now accepts `limit`/`offset` with `Int.MAX_VALUE` defaults. The admin sessions page displays the 100 most recent active sessions with a subtitle: "Showing the 100 most recent of N active sessions" when capped
- **Audit log pagination retrofitted** — inline pagination HTML replaced with the shared `paginationControls()` component. Pagination links now include htmx attributes for partial-page swaps (previously caused full-page reloads)
- **RBAC assign/unassign toast feedback** — "User assigned to role.", "User removed from role.", "Member added to group.", "Member removed from group." toasts on all assignment actions
- **RBAC duplicate assignment prevention** — search results exclude already-assigned users via the `exclude` query param. POST handlers now check `AdminResult` and redirect gracefully on failure
- **Portal `portal-user__email` → `portal-user__handle`** — CSS class renamed to match actual content (renders username, not email)
- **JS modernized to ES2020+** — `var` → `const`/`let`, `function` → arrow functions, template literals, optional chaining across `settings.js`, `branding.js`, `confirm-dialog.js`, `auth.js`, `update-check.js`. IIFE wrappers retained for strict mode. `branding.js` `this` references replaced with closed-over parameters
- **`renderFragment()` trims whitespace** — prevents CSS `:not(:empty)` from being defeated by stray text nodes in htmx swap responses

### Fixed

- **Scope toggle JS bug** — `settings.js` compared `sel.value === 'application'` but the `<option>` emits `value = "client"`. Application-scoped role creation was silently broken — the app selector field never appeared. Fixed to match the emitted value
- **Entity picker dropdown clipped** — `.ov-card { overflow: hidden }` clipped the absolute-positioned dropdown. Added `.ov-card:has(.entity-picker) { overflow: visible }` scoped override
- **Entity picker spinner invisible** — htmx adds `.htmx-request` to the indicator element itself (not parent) when using `hx-indicator` with an explicit ID. Added self-class selector `.entity-picker__spinner.htmx-request` alongside the parent-child selector
- **Entity picker input missing `name` attribute** — htmx had nothing to serialize into the query string. Search requests never fired. Added `name="q"`

### Removed

- **`PortalView.mfaChallengePage()`** — 70 lines of dead code. MFA challenge during portal login is handled by `AuthView.mfaChallengePage()` in the OAuth auth flow
- **`AdminView.loginPage()`** — dead since OAuth PKCE migration (v1.2.0). Old password login page
- **`AdminView.workspaceRedirector()`** — dead, localStorage redirect logic no longer used
- **`loginPageImpl()`** in `AuthViews.kt` — 60-line implementation backing the removed facade
- **`workspaceRedirectorImpl()`** in `DashboardViews.kt` — 20-line implementation backing the removed facade
- **Unused `val user` binding** in `AdminService.unlockUser()` — findById call kept as not-found guard, dropped unused variable assignment
- **`allUsers` parameter** removed from `roleDetailPageImpl`, `groupDetailPageImpl`, `AdminView.roleDetailPage`, `AdminView.groupDetailPage`, and their route handlers — replaced by the entity picker search pattern. Eliminates full table scans on every role/group detail page load

---

## [1.4.0] - 2026-04-02

### Added

- **Auto-update version discovery** — background coroutine checks a remote manifest (`latest.json`) every 6 hours for new KotAuth releases. Cached result is served at `GET /health/version` with `currentVersion`, `latestVersion`, `updateAvailable`, `urgency`, `releaseUrl`, and `checkedAt` fields. The check is non-blocking, failure-tolerant (silent degradation on network errors, 404s, or malformed responses), and runs on `Dispatchers.IO` via `withContext`
- **Admin console update chip** — when an update is detected, a compact notification chip appears in the topbar-right cluster showing the available version and a link to release notes. Security updates (`urgency: "security"`) render in red with `role="alert"`. Routine updates use the accent color with `role="status"`. A pulsing dot draws initial attention (3 cycles, respects `prefers-reduced-motion`). Dismissible via localStorage — dismissal is version-scoped, so a newer release supersedes prior dismissals automatically
- **Rail version amber styling** — the version label at the bottom of the icon rail turns amber when an update is available, providing a secondary visual hint alongside the topbar chip
- **`KAUTH_UPDATE_CHECK` env var** — opt-out for air-gapped deployments. Set to `false` to disable outbound version checks entirely. Enabled by default
- **`KAUTH_UPDATE_CHECK_URL` env var** — override the manifest URL for custom mirrors or internal proxies. Defaults to `https://inumansoul.github.io/kotauth/latest.json`
- **GitHub Actions manifest workflow** — `.github/workflows/manifest.yml` triggers on `release: published`, generates `latest.json` on the `gh-pages` branch, and pushes it to GitHub Pages. Handles first-run (`gh-pages` branch creation), skips pre-releases, and derives urgency from `[security]` tag in the release body
- **Semver comparison** — internal `isNewer()` function handles `v` prefixes, pre-release suffixes, 2-part versions, and 4-part versions (truncated to 3). Pre-release of the same base version (e.g., `1.4.0-rc1` vs `1.4.0`) correctly returns `false`

### Changed

- **Quickstart compose** — `KAUTH_UPDATE_CHECK: "false"` added to `docker-compose.quickstart.yml` to avoid update banners in evaluation environments

---

## [1.3.3] - 2026-04-02

### Added

- **CSS tooltip component** — pure-CSS tooltip using `data-tooltip` attribute and `::after` pseudo-element. Uses `:has(:disabled)` to show `cursor: not-allowed` on wrappers containing disabled elements. New `tooltip-wrap` class available across the admin console
- **Disabled button styling** — `.btn:disabled` now renders at 40% opacity with `cursor: not-allowed`, consistent across all button variants
- **Toast notifications for user actions** — enable/disable user, revoke all user sessions, and resend verification email now show toast feedback ("User disabled.", "User enabled.", "All sessions revoked.", "Verification email sent.")

### Changed

- **REST API partial updates** — `PUT /users/{id}` and `PUT /applications/{id}` now accept partial payloads. All fields in `UpdateUserRequest` (`email`, `fullName`) and `UpdateApplicationRequest` (`name`, `accessType`, `redirectUris`) are optional — omitted fields retain their current values. Enables PATCH-style updates via PUT without requiring the full object
- **Audit log filter auto-submit** — the event type `<select>` now fires immediately on change via `hx-trigger="change"`, no longer requires clicking the "Filter" button
- **Settings save button feedback** — all admin settings forms (general, SMTP, identity providers, security, branding) now disable the submit button and show "Saving…" text during form submission, providing immediate visual feedback
- **Danger zone card — dynamic text** — the disable/enable user card in the user detail view now reflects the current user state: "Disable this user" / "Enable this user" with context-appropriate descriptions
- **Send Reset Email — tooltip on disabled state** — when SMTP is not configured, the disabled button is wrapped in a `tooltip-wrap` that shows "Configure SMTP to enable password reset emails" on hover
- **Webhook delivery count label** — the "Recent Delivery History" section header now shows "(last N)" indicating the visible delivery count, replacing the previous silent truncation
- **Portal session revoke confirmation** — individual session revoke buttons in the self-service portal now require confirmation via the custom dialog ("Revoke this session? The user will be signed out immediately.")

### Fixed

- **Verification email toast** — resending a verification email previously showed "Profile saved." — now correctly shows "Verification email sent."

---

## [1.3.2] - 2026-04-01

### Added

- **Workspace "Revoke all sessions"** — new button in the sessions page header with confirmation dialog. Revokes all active sessions across all users in a workspace. New `SessionRepository.revokeAllForTenant()` port method. Audit event `ADMIN_SESSIONS_REVOKED_ALL` recorded with count
- **SMTP test email button** — "Send Test Email" button on the SMTP settings page (visible when SMTP is configured). Sends a branded test email to the admin's address via `AdminService.sendTestEmail()`. New `EmailPort.sendTestEmail()` method. Audit event `ADMIN_SMTP_TEST` recorded
- **Webhook recovery sweep** — background coroutine runs every 5 minutes, queries pending deliveries via `findPending()`, and retries them. Marks deliveries as FAILED if their endpoint was deleted. Follows the session cleanup job pattern
- **Workspace logo in admin console** — workspace logos (configured in branding settings) now display in the topbar switcher badge, dropdown items, and workspace detail page. Falls back to letter initial when no logo is configured. New `workspaceAvatar()` reusable component with BEM `--sm` modifier for compact contexts
- **`WorkspaceStub` data class** — replaces `Pair<String, String>` for workspace navigation data. Carries `slug`, `name`, and `logoUrl`. Used across all admin shell calls and view functions
- **`ClientDisplayInfo` + `resolveClientLinks` helper** — resolves application IDs to both display name and OAuth `clientId` for correct URL linking in the audit log
- **Audit log event badges** — event types rendered as color-coded badges using the existing badge component: green (success), red (failure), amber (revocation/warning), blue (informational), gray (admin CRUD)
- **Audit log `<optgroup>` filter** — 55 event types grouped into 7 categories (Login & Registration, Tokens & Authorization, Sessions, Admin Actions, Email & Password, User Self-Service, MFA) for easier scanning

### Changed

- **Composite role expansion → recursive CTE** — `expandCompositeRoles()` replaced BFS loop (1 query per tree level) with a single `WITH RECURSIVE` CTE query. Uses `UNION` to prevent infinite cycles on circular role hierarchies
- **Typed webhook events** — `WebhookEvent` string constants replaced with `WebhookEventType` enum. Type-safe across model (`WebhookEndpoint.events`, `WebhookDelivery.eventType`), service (`dispatch`, `createEndpoint`), repository serialization, routes, views, and tests. Invalid event types are now impossible at compile time. No migration needed — DB column stays TEXT, string values unchanged
- **Audit log page header** — hand-rolled markup replaced with shared `pageHeader()` component
- **Audit log event types human-readable** — table rows display `login success` instead of `LOGIN_SUCCESS`, matching the filter dropdown format
- **Audit log client column linked** — client names now link to the application detail page using the OAuth `clientId` slug
- **Session revoke toast feedback** — single-session revoke shows "Session revoked." toast. Revoke-all shows "All sessions revoked." Both use distinct `?saved=` values (`revoked` / `revoked_all`)

### Fixed

- **CTE queries use `prepareStatement().executeQuery()`** — Exposed's `Transaction.exec()` internally calls `executeUpdate` which throws when a SELECT result is returned. Both CTE methods (`expandCompositeRoles`, `findAllAncestorGroupIds`) now use `connection.prepareStatement(sql, false).executeQuery()` for correct result set handling. Fixes login failure introduced by the CTE migration

---

## [1.3.1] - 2026-03-30

### Added

- **Client-side password validation** — real-time inline checklist on all password fields (register, reset-password, portal change-password, admin user creation). Shows per-tenant policy requirements as the user types: minimum length, uppercase, numbers, special characters. Appears on first keystroke, green checkmarks for met rules, red after blur. Confirm password mismatch shown on blur. `aria-live="polite"` for screen readers
- **Auto-dismissing toast notifications** — replaces persistent `?saved=true` banners across all admin settings pages and portal pages. Slides in at top-right, auto-removes after 5 seconds. Server renders `data-toast-msg` on `<body>`; JS displays and cleans the URL. Falls back to no-JS gracefully
- **`EnglishStrings` object** — centralized English strings for i18n preparation (v2.x). Password field labels, toast messages, and validation text extracted. Strings are added incrementally as views are touched
- **`FRONTEND_COMPONENTS.md`** — documents the three notification patterns (toast, alert, notice) with use cases, decision matrix, and CSS architecture (layer pattern, token sources)

### Security

- **CSP compliance** — all 9 inline `onclick`/`onchange` handlers in portal MFA pages replaced with `data-action` attributes + event delegation in `portal/mfa.js`. No more `'unsafe-inline'` violations for script execution
- **QRCode.js bundled locally** — removed CDN dependency (`cdnjs.cloudflare.com`). Library now bundled in `kotauth-portal.min.js`. Portal MFA enrollment works fully offline / air-gapped

### Changed

- **CSS token architecture** — created `base/tokens-shared.css` with structural tokens (spacing, typography, status colors) shared across all 4 bundles. Admin's `tokens.css` imports it as a superset. Auth and portal bundles import it directly. Fixes toast and password validation rendering on portal pages
- **Button CSS refactored to BEM layers** — `shared/button.css` defines the base contract (font, radius, focus-visible, color modifiers). `auth/button.css` defaults to block layout (full-width CTA). `portal/button.css` defaults to compact layout (inline actions). Portal buttons now respect tenant `border-radius` (was broken — used admin-only `--radius-sm` token)
- **Form CSS refactored to shared base** — `shared/form.css` defines common input styling (background, border, radius, focus, password toggle). Auth and portal layers add context-specific layout
- **Alert CSS follows re-export pattern** — `portal/alert.css` re-exports `shared/alert.css` (consistent with button and form pattern)
- **Portal button coherence** — primary hover uses color swap (was opacity), `font-weight` aligned to 600, `letter-spacing` added, `focus-visible` outline added, active state uses `scale(0.98)` matching auth
- **Auth button** — `font-family: inherit` → `var(--font-sans)` for explicit consistency
- **Semantic color tokens** — `--color-success` and `--color-error` aliases added to `tokens.css`, used by toast and password validation CSS
- **Register page social login icons** — Google and GitHub SVG icons now render correctly on the create account page (were empty `span` elements)
- **Portal MFA scripts extracted** — 140 lines of inline JS moved to `frontend/js/portal/mfa.js` bundle. Modern JS (const/let, async/await, descriptive names). `window._codes` replaced with module-scoped variable
- **Toast messages use `EnglishStrings`** — all 11 toast messages centralized for i18n readiness

---

## [1.3.0] - 2026-03-30

### Security

- **`KAUTH_SECRET_KEY` required in all environments** — the server refuses to start without a 32+ character key. Eliminates the previous dev-mode fallback that stored TOTP secrets in plaintext and silently discarded SMTP passwords
- **RSA private keys encrypted at rest** — tenant JWT signing keys are now AES-256-GCM encrypted in the database. Existing plaintext keys are automatically encrypted on first startup via `KeyEncryptionMigration`
- **`KAUTH_ADMIN_BYPASS` removed** — the direct-credential admin login path has been fully deleted (route handler, tests, docs). Admin authentication is exclusively via OAuth PKCE. For emergency recovery, use `java -jar kauth.jar cli reset-admin-mfa --username=admin`
- **MFA challenge rate limiting** — `POST /t/{slug}/mfa-challenge` is now limited to 5 attempts per 5 minutes per IP. Prevents brute-forcing of 6-digit TOTP codes within the MFA pending window
- **Password reset rate limiting** — `POST /t/{slug}/reset-password` is limited to 3 attempts per 5 minutes per IP. Prevents repeated password attempts against a leaked reset token
- **`findById` tenant scoping** — `UserRepository.findById(userId)` now requires `tenantId`, enforced at the database query level. Cross-tenant user lookups are structurally impossible. 33 call sites updated, redundant post-call tenant checks removed
- **Client secret removed from URL** — regenerated client secrets are no longer passed via `?newSecret=` query parameter (visible in logs, browser history, referrer headers). Uses a server-side `FlashStore` with one-time read semantics
- **FK indexes** — V29 migration adds 12 missing foreign key indexes across `sessions`, `authorization_codes`, `audit_log`, `composite_role_mappings`, `group_roles`, and 5 tenant-scoped tables. Prevents sequential scans on `DELETE CASCADE` operations

### Added

- **CLI infrastructure** — the JAR now supports subcommands: `java -jar kauth.jar cli <command>`. Dispatched in `Application.kt`, no CLI framework dependency
- **`generate-secret-key` command** — generates a cryptographically secure 32-byte hex key for `KAUTH_SECRET_KEY`. Pure crypto, no database connection required
- **`reset-admin-mfa` command** — resets MFA enrollment for a locked-out admin on the master tenant. Connects to the database without running Flyway migrations
- **HTTP response compression** — gzip (priority 1.0) and deflate (priority 0.9) with 1024-byte minimum. Images excluded
- **Static asset cache headers** — CSS and JS get `Cache-Control: public, max-age=31536000` with `?v=` version query param for cache busting. HTML gets `Cache-Control: no-cache`
- **Global htmx loading indicator** — thin accent-colored progress bar at the top of every admin page during htmx requests
- **Rate limiting documentation** — `docs/RATE_LIMITING.md` covering all protected endpoints, architecture, memory management, and planned additions
- **`AdminDisplayHelpers`** — shared display utility for resolving user IDs and client IDs to human-readable names across admin views
- **Makefile targets** — `make generate-key` and `make reset-mfa USER=admin` convenience wrappers

### Changed

- **`EncryptionService` is always available** — constructor takes non-nullable `String`. The `isAvailable` property and all branching on it have been removed from `EncryptionPort`, `PostgresMfaRepository`, `PostgresTenantRepository`, `AdminSettingsRoutes`, `ServiceGraph`, `Application.kt`, and health/welcome routes
- **`AdminService` expanded** — new methods: `getUser`, `listUsers`, `toggleUserEnabled`, `createWorkspace`. Route handlers no longer call `UserRepository` directly
- **ADR-04 compliance** — `AdminUserRoutes`, `AdminSessionAuditRoutes`, and `ApiUserRoutes` no longer receive `UserRepository` as a parameter. All user operations go through `AdminService`
- **Workspace creation validation** — slug format, reserved names, uniqueness, and display name checks moved from the route handler into `AdminService.createWorkspace()` with sealed `AdminResult` return
- **`resolveEffectiveRoles` performance** — replaced N+1 ancestor group traversal with a single recursive CTE query. For 3 groups × 5 levels deep, reduces from ~15 DB roundtrips to 1
- **Rate limiter hardened** — `InMemoryRateLimiter` now tracks `lastAccess` for LRU eviction, configurable `maxKeys` cap (default 10,000), two-phase eviction (prune expired, then LRU). Dead `hitCount` field removed
- **IdP form toggle** — enabling a new identity provider now respects the toggle value on first save (was hardcoded to `false`)
- **Workspace creation form** — removed branding card (accent color + logo URL were silently discarded). Added `(optional)` to Issuer URL label. Registration policy checkboxes now use `check-row__body` pattern with descriptions
- **Audit log** — breadcrumb uses standard component with `›` separators. Filter bar inside htmx swap target so Clear button updates correctly. `activeAppSection` fixed from `"events"` to `"audit"`
- **Error alerts** — 3 legacy `alert alert-error` instances in `UserViews` replaced with `notice notice--error`
- **RBAC tables** — `key-table` class replaced with `data-table` across all RBAC detail views
- **MFA tables** — bespoke `mfa-user-*` classes replaced with `data-table__*` for consistency
- **Rail navigation** — workspace-dependent nav items render as disabled ghosts when no workspace is selected
- **Topbar search** — wired to navigate to user list with `?q=` when a workspace is selected. Disabled with tooltip when no workspace is selected
- **Application detail** — client secret display uses `copy-field` pattern with copy button (was plain text in a notice)
- **Session revoke** — per-row confirmation dialog added on user detail page
- **Webhook deliveries** — removed redundant `.take(50)` in view (already limited at query level)
- **Workspace detail** — removed duplicate "New Application" CTA from section label
- **Create application** — sidebar hidden (was rendering empty)
- **Roles/Groups headers** — `span` → `p` for subtitle element

### Removed

- **`KAUTH_ADMIN_BYPASS`** environment variable and all supporting code
- **`EncryptionPort.isAvailable`** property — encryption is always available
- **`isAvailable` branching** in `PostgresMfaRepository`, `PostgresTenantRepository`, `AdminSettingsRoutes`, `ServiceGraph` session key derivation
- **Direct `UserRepository` access** from `AdminUserRoutes`, `AdminSessionAuditRoutes`, `ApiUserRoutes`, `ApiRoutes`

---

## [1.2.1] - 2026-03-27

### Added

- **`/authorize` endpoint** — industry-standard OAuth authorization URL. `GET /authorize` validates params and sets a server-side auth context cookie. `POST /authorize` processes credentials with the full security pipeline (lockout, MFA, rate limiting, password expiry). Replaces the old `/protocol/openid-connect/auth` (backward compat redirect preserved) and eliminates all hidden OAuth form fields
- **Server-side auth context cookie** (`KOTAUTH_AUTH_CONTEXT`) — signed cookie scoped to `/t/{slug}` replaces hidden form fields for carrying OAuth state through the login flow. Fixes double-login in incognito mode and survives page refreshes
- **JS bundling with esbuild** — source files in `frontend/js/`, compiled into 4 minified bundles: `kotauth-admin.min.js` (53KB), `kotauth-auth.min.js` (1.6KB), `kotauth-portal.min.js` (1KB), `branding.min.js` (3.3KB). SRI integrity hashes generated at build time via `js-integrity.properties`
- **Password show/hide toggle** — eye icon on all 5 password fields across login, register, and reset-password forms. Server-rendered SVGs with CSS-based icon swap. New `auth.js` for auth page interactions
- **Custom confirmation dialog** — `<dialog>` element replaces browser `confirm()` across admin console and portal. Themed via CSS custom properties, backdrop fade + card fade-in-up animation. No `window.confirm()` fallback
- **Portal brand logo** — tenant-configured `logoUrl` displayed in portal topbar and sidebar, replacing initials when available
- **Session revocation DB check** — portal and admin session guards now validate the backing DB session on every request. Revoking a session from the admin console immediately invalidates the user's cookie
- **Swagger UI bundled locally** — CSS/JS assets served from `/static/swagger/`, no CDN dependency. Works in air-gapped environments. Branded dark topbar with accent authorize button

### Security

- **`POST /t/{slug}/login` removed** — no standalone credential endpoint exists. All authentication goes through `POST /authorize` which enforces the full security pipeline. The only direct-auth path is `POST /admin/login`, gated by `KAUTH_ADMIN_BYPASS`
- **Rate limiting on `POST /authorize`** — login rate limiter enforced on the new authorize endpoint
- **SRI integrity hashes** on all JS bundle `<script>` tags — prevents tampering with static assets
- **CSP updated** — allows Google Fonts (`style-src`, `font-src`) and HTTPS tenant logos (`img-src https:`)

### Changed

- **OIDC discovery `authorization_endpoint`** now advertises `/t/{slug}/authorize`
- **Portal + admin PKCE redirects** point to `/authorize`
- **All "Sign in" links** in auth views and email templates point to `/t/{slug}/account/login` (portal login which starts a proper OAuth flow)
- **Post-registration redirect** — OAuth-aware: if auth context cookie exists, returns to `/authorize?registered=true`; standalone → `/account/login`
- **Email templates** — shared `buildEmailHtml()` layout with TenantTheme branding (accent button, logo, font, border radius). Responsive table-based layout with `max-width:480px` fluid fallback
- **Sessions/audit tables** — user IDs resolved to clickable usernames, client IDs resolved to application names
- **Audit log page size** reduced from 50 to 20 per page
- **Confirmation dialogs** added to disable-user and revoke-all-sessions buttons
- **Error in URL** fixed — send-reset-email failure uses `?saved=` flag instead of URL-encoded error message
- **htmx: user search** — debounced `hx-get` with `hx-replace-url`, "N of M users" subtitle
- **htmx: audit filter** — in-place table update with `hx-push-url`, pagination carries htmx attributes
- **All `<script>` tags** now use `defer` for non-blocking page rendering
- **Dockerfile** — stage 1 renamed `frontend-build`, includes JS compilation + SRI generation

### Removed

- **`LoginRoutes.kt`** — deleted entirely. No `/t/{slug}/login` route exists
- **Hidden OAuth form fields** — ~70 lines of `<input type="hidden">` elements removed from login and MFA pages
- **Individual JS source files** from `src/main/resources/static/js/` — replaced by compiled bundles

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

[Unreleased]: https://github.com/inumansoul/kotauth/compare/v1.2.1...HEAD
[1.2.1]: https://github.com/inumansoul/kotauth/compare/v1.2.0...v1.2.1
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
