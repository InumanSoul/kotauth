# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.22.0] - 2026-08-21

Machine-to-machine (M2M) onboarding release. Applications now declare an
explicit set of OAuth2 grant types, and the token endpoint refuses any grant
a client isn't registered for. Confidential applications receive their
client secret at creation instead of requiring a separate "Regenerate"
step, and a machine-to-machine client no longer needs a redirect URI it
will never use.

> **BREAKING CHANGE**
>
> Migration `V59` backfills every existing client with the grants it
> already effectively had — confidential clients get all three grants,
> public clients get `authorization_code` + `refresh_token` — so neither
> changes behavior. **Bearer-only clients are backfilled with no grants
> and will be refused (`unauthorized_client`) at the token endpoint after
> upgrade.** This is deliberate: a bearer-only client validates tokens and
> initiates no flows, which is what the access type means. If a
> bearer-only client was in fact driving a flow, change its access type to
> confidential or public and select the grants it actually uses.
>
> **This also affects `POST /api/v1/applications` going forward.** When
> `grantTypes` is omitted, the API defaults to `authorization_code` +
> `refresh_token` — never to `client_credentials`, even for a confidential
> application, because defaulting a confidential client into
> machine-to-machine capability is the over-permissioning this release
> exists to end. A provisioning script that has been creating confidential
> clients for machine-to-machine use must now send `grantTypes`
> (containing at least `client_credentials`) explicitly on every create
> call. If it doesn't, the client it creates will be refused
> (`unauthorized_client`) at the token endpoint the first time it tries to
> get a token.

### Added

- **Explicit grant types on applications.** Each application now carries an
  explicit set of OAuth2 grant types (`authorization_code`,
  `client_credentials`, `refresh_token`), selectable on the admin
  create/edit forms and via the REST API. `POST /api/v1/applications`
  accepts an optional `grantTypes` array, defaulting to
  `authorization_code` + `refresh_token` when omitted. **This is a
  behavior change for API consumers creating confidential
  machine-to-machine clients** — see the breaking-change note above.
- **Client secret issued at creation.** A confidential application now
  receives its client secret the moment it's created — shown once in a
  copy-now banner in the admin UI, and returned once as `clientSecret` in
  the `POST /api/v1/applications` response. Previously a new confidential
  client had no secret at all, and the operator's first action had to be
  "Regenerate" — an action whose name implies it replaces something that
  never existed.
- **Redirect URI is conditional, not mandatory.** A redirect URI is now
  required only when the application uses the `authorization_code` grant.
  A machine-to-machine (`client_credentials`-only) application can be
  registered with none — previously operators had to invent a fake one.
- **Token audience settable at creation.** The application create form now
  exposes the token `aud` override, previously only editable afterward via
  Edit.
- **Authorized APIs reachable from the application page.** The application
  detail page now links directly to its Authorized APIs screen. The page
  already existed and worked, but nothing linked to it — operators had to
  type the URL, and skipping that step makes every token request targeting
  an API fail with nothing pointing at the cause.
- **APIs moved beside Applications.** The APIs screen moves from Settings
  to sit next to Applications in the sidebar, and its subtitle now reads
  "APIs (resource servers)" so the UI's term and the codebase's RFC 8707
  term are greppable against each other. Routes are unchanged, so existing
  bookmarks and links still work.
- **Grant types are readable, updatable, and visible.** `GET`/`PUT
  /api/v1/applications/{id}` now return and accept `grantTypes`, and the
  application detail page shows them on the Overview card. Previously
  grants were write-once-on-create with no way back — a confidential
  application backfilled with all three grants could never be demoted to
  `public` over the API, because removing `client_credentials` had no
  path — and an operator debugging an `unauthorized_client` error had to
  open Edit to see what was registered.

### Changed

- **The token endpoint now refuses any grant a client isn't registered
  for**, returning `unauthorized_client`. Previously `client_credentials`
  was gated only on the client being confidential — any confidential web
  app could mint machine-to-machine tokens regardless of intent.
- Backups now carry each application's grant types. Backups written before
  this change restore with grants derived from the client's access type
  (the same rule the `V59` migration backfill uses).
- The Client ID field's browser-side validation pattern is narrowed to
  match the server's rule — the form previously accepted input the server
  then rejected.

### Fixed

- The `authorization_code` grant now verifies the code was issued to the
  client redeeming it (RFC 6749 §4.1.3), rejecting the exchange with
  `invalid_grant` when it wasn't.
- The APIs page now activates the Applications rail, matching where its
  nav entry actually lives. It previously switched to the Settings rail,
  which no longer contains an APIs link, so the entry appeared to vanish
  with no way back to the app context.
- `GET /authorize` now returns `unauthorized_client` immediately when the
  client isn't registered for the `authorization_code` grant, instead of
  rendering the full login page and only failing at code issuance —
  after the user has already entered their password and MFA.
- A bearer-only application can no longer be registered with grant types
  selected. It validates tokens and initiates no flow, so any grant on it
  ran with neither PKCE (it isn't public) nor client authentication (it
  isn't confidential).
- The REST API now rejects a `grantTypes` value it doesn't recognize
  instead of silently creating the client without it.

### Migrations

- `V59__client_grant_types.sql` — adds the `client_grant_types` table and
  backfills existing clients: confidential clients get all three grants,
  public clients get `authorization_code` + `refresh_token`, bearer-only
  clients get none (see the breaking change above).

---

## [1.21.0] - 2026-07-17

API-first release. 25 new REST endpoints across users, applications, sessions,
workspace, webhooks, resource servers, API keys, and passkeys. Introduces
per-key + per-tenant rate limiting on write endpoints. Adds a second auth-page
layout variant (SPLIT) alongside the existing centered layout.

### Added

#### REST API endpoints

**Users**
- `POST /api/v1/users/{userId}/enable` — re-enable a disabled user.
- `DELETE /api/v1/users/{userId}/mfa/reset` — reset MFA enrollments and recovery codes for a user.
- `POST /api/v1/users/{userId}/revoke-sessions` — revoke every active session for a user.
- `GET /api/v1/users/{userId}/sessions` — list active sessions for a user.
- `GET /api/v1/users` — now supports `?limit=&offset=&search=` (envelope with `meta.total`).
- `UserDto` gains `requiredActions`, `isLocked`, `createdAt`.
- `GET /api/v1/users/{userId}/passkeys` — list a user's passkey credentials.

**Applications**
- `POST /api/v1/applications` — create an OAuth2/OIDC client; returns one-time client secret for confidential clients.
- `POST /api/v1/applications/{appId}/regenerate-secret` — rotate the client secret (one-time in response).

**Groups & Roles**
- `POST /api/v1/groups/{groupId}/roles/{roleRef}` — assign a role to a group (atomic).
- `DELETE /api/v1/groups/{groupId}/roles/{roleRef}` — remove a role from a group.

**Sessions**
- `GET /api/v1/sessions` — new query params: `user_id`, `application_id`, `active_only`, `limit`, `offset`.

**Workspace**
- `GET /api/v1/workspace` — read-only tenant configuration surface (sign-in methods, security policy, MFA, magic-link TTL, email OTP limits, portal layout). **SMTP credentials are omitted entirely.**

**Webhooks**
- `GET /api/v1/webhooks`, `POST /api/v1/webhooks` (returns HMAC signing secret one-time), `DELETE /api/v1/webhooks/{endpointId}`.

**Resource servers**
- `GET/POST/PUT/DELETE /api/v1/resource-servers`, `GET/PUT /api/v1/applications/{appId}/authorized-resource-servers`.

**API keys**
- `GET /api/v1/api-keys`, `POST /api/v1/api-keys` (raw key returned one-time), `DELETE /api/v1/api-keys/{id}` (soft-revoke; bootstrap-provisioned keys rejected with 403).

**Passkey admin**
- `DELETE /api/v1/passkeys/{credentialPk}` — revoke a passkey credential.

#### Scopes

- `workspace:read`, `webhooks:read`, `webhooks:write`, `resource_servers:read`, `resource_servers:write`, `api_keys:read`, `api_keys:write`.

#### Auth UI

- **SPLIT login layout** — new auth page variant with a branded left panel (background image + tagline) and the auth card on the right. Existing tenants stay on the default CENTERED layout — no visual regression.
- Admin Branding page gains three inputs: layout picker, tagline (falls back to workspace name), background image URL (`http(s)://` only, validated at write time and escape-hardened at render time).

#### Reliability

- Write endpoints (POST/PUT/PATCH/DELETE under `/api/v1/*`) are rate-limited to 60 requests per 60-second window per API key per workspace. Reads (GET) are unrestricted. Exceeded requests receive `429 Too Many Requests` with a `Retry-After` header.
- OpenAPI spec bumped to `1.1.0` with per-endpoint audit — every new endpoint has schema, examples, and normalized shared error responses.

### Changed

- **BREAKING: `DELETE /api/v1/applications/{appId}` semantics changed** — previously soft-disabled the application (`enabled = false`); now soft-deletes it (`is_deleted = true`). Historical audit trails still resolve `client_id → application`, but the row no longer appears in `GET /applications`, and re-creating a soft-deleted `client_id` returns `409 Conflict` (uniqueness includes soft-deleted rows). Consumers relying on the old soft-disable behavior must call `PUT /api/v1/applications/{appId}` with an explicit `enabled: false` field going forward.
- `GET /api/v1/users` response shape changed to a pagination envelope with `meta.total`, `meta.offset`, `meta.limit`. The `data` array is unchanged in shape; consumers that only read `data` are unaffected.
- `UserDto` gains three new fields (`requiredActions`, `isLocked`, `createdAt`) — additive for encoders, backward-compatible for typical decoders.

### Fixed

- API-key `DELETE` now rejects bootstrap-provisioned keys (`KAUTH_BOOTSTRAP_API_KEYS`) with `403 Forbidden`, matching the admin UI and OpenAPI documentation.
- SPLIT layout background URL is percent-encoded before injection into the CSS `url('…')` literal, closing a CSS-injection vector on the operator-only field.

### Migrations

- `V57__login_layout.sql` — adds `login_layout` (`NOT NULL DEFAULT 'CENTERED'`), `login_background_url`, `login_tagline` to `workspace_theme`.
- `V58__application_soft_delete.sql` — adds `is_deleted BOOLEAN NOT NULL DEFAULT FALSE` to `clients` with a partial index on `(tenant_id) WHERE is_deleted = FALSE`.

---

## [1.20.2] - 2026-07-11

Completes RFC 8707 resource-indicator support for the Email OTP back-channel flow so access tokens can target an API audience without changing the ID token audience.

### Added

- `POST /api/v1/auth/send-otp` accepts optional `resources` and persists the normalized indicators with the OTP challenge.
- Successful OTP verification validates that every requested resource is enabled and authorized for the originating client, then binds the resources to the single-use authorization code.
- Email OTP resource-indicator usage is documented in the operator guide and OpenAPI schema.

### Changed

- `EmailOtpService` now requires `ResourceServerRepository` composition and resolves requested resources in one tenant-scoped batch.

### Migrations

- `V56__email_otp_challenge_resources.sql` — adds the non-null JSONB `resources` column to `email_otp_challenges` with an empty-list default.

---

## [1.20.1] - 2026-07-10

Polish release for v1.20.0 Passkeys — consolidates the two overlapping "disable password sign-in" flags into one canonical column, replaces the split "Authentication Methods" and "Passkeys" cards with a single Sign-in Methods grid, restructures the admin Security rail to actually hold security pages, and nests MFA, Passkeys, and Sessions under a Security parent group in the portal nav.

### Changed

- **Sign-in Methods grid** replaces the two-card auth methods panel on the Security Policy page. One row per method (Password, Passkey, Email magic link, Email OTP, Google, GitHub). Locked rows show an inline "SMTP required — Set up SMTP" prompt when SMTP is not configured.
- **`tenant.password_login_disabled` column removed.** The v1.20.0 flag is consolidated into `security_config.passwordLoginEnabled`. V55 migration back-fills existing tenant state. Same SMTP hard-gate, same error codes (`SmtpRequired`, `NoMethodsEnabled`).
- **Admin sidebar Security rail restructured**: now contains Security Policy (moved from Settings), MFA, Passkeys, Sessions. The Security rail landing page changes from `/sessions` to `/settings/security`.
- **Portal nav gains a Security parent group**: Overview, Two-Factor Auth, Passkeys, Sessions. Route paths unchanged for backward compatibility.
- **Portal sessions page extracted** into its own route at `/t/<slug>/account/sessions`. The old Security page becomes an Overview summarising each child feature.

### Migrations

- `V55__consolidate_password_login_flag.sql` — updates `security_config.passwordLoginEnabled=false` for tenants that had set `password_login_disabled=true`, then drops the column.

### Fixed

- `GET /change-password` activePage now correctly activates the Security group without highlighting the Overview leaf.
- Dead `?saved=true` query parameter removed from the change-password GET handler; the POST success path redirects to login rather than back to the form.

---

## [1.20.0] - 2026-07-09

Passkeys / WebAuthn — passwordless sign-in via device biometrics or hardware keys.

### Added

- **Passkeys as a passwordless-primary sign-in method.** Users can enroll one or more WebAuthn credentials (platform authenticators like Face ID / Windows Hello, roaming authenticators like YubiKey) via the portal at `/t/<slug>/account/passkeys` and sign in with an explicit "Sign in with a passkey" button or conditional-mediation autofill on the username field.
- **Per-tenant `passkeys_enabled` toggle** on the workspace security page (default: on).
- **Per-tenant passwordless-only mode** via the new `password_login_disabled` column. When enabled, the login page hides the password field and shows "Sign in with a passkey" + "Get a magic link" only. Recovery reuses the existing magic-link password-reset flow, ending on a "Enroll a passkey on this device" landing (see [ADR-16](docs/adr/ADR-16-passkeys-sibling-to-password.md) and the [operator guide](docs/guides/passkeys.md)).
- **Hard SMTP gate on passwordless-only tenants.** Admin UI and backend both reject enabling `password_login_disabled` without configured SMTP — prevents operator-created lockout (see [ADR-17](docs/adr/ADR-17-smtp-hard-gate-passwordless-tenants.md)). The same gate blocks per-user "Reset all passkeys" when it would strand a user.
- **Admin per-user passkey management** at `/admin/workspaces/<slug>/users/<id>` — list credentials (with device name from bundled AAGUID lookup), revoke individually, "Reset all passkeys". Simultaneously adds the previously-missing admin "Reset MFA" action.
- **CLI `reset-admin-passkeys`** — master-tenant emergency reset, sibling of `reset-admin-mfa`.
- **Passkey authentication satisfies MFA** — passkey with user verification is inherently multi-factor per FIDO2. Users with `mfa_policy=required` skip the TOTP challenge when signing in with a passkey.
- **AAGUID → device-name lookup** bundled at `webauthn/aaguid-names.json` (11 baseline entries: Apple, Windows Hello, YubiKey 5 series, iCloud Keychain, 1Password, Bitwarden, Google Password Manager). Unknown authenticators render as "Unknown authenticator".

### Migrations

- `V54__webauthn_credentials.sql` — new `webauthn_credentials` table (13 columns, one row per credential); new `tenants.passkeys_enabled BOOLEAN NOT NULL DEFAULT TRUE` and `tenants.password_login_disabled BOOLEAN NOT NULL DEFAULT FALSE` columns. All defaults preserve v1.19.3 behaviour for existing rows.

### Security

- **Per-credential replay defense** via the WebAuthn sign counter. Cloned authenticators are detected on counter mismatch; the credential is auto-revoked and `PASSKEY_REPLAY_REJECTED` audit event is emitted.
- **Attestation policy: none** (accept all authenticators) in v1.20.0. Enterprise attestation via FIDO MDS is deferred to a v1.20.1 candidate.
- **User verification: `preferred`** — modern platform passkeys always supply UV; older U2F-only keys still work. Per-tenant `required` override deferred to v1.20.1.
- **Rate limiting** on `/passkeys/authenticate/finish` — 10 attempts per 60 seconds per IP, fail-closed on Redis outage.
- **Backup exclusion** — passkey credentials are not included in `BackupExport` (device-bound, would break RP ID on restore to a different origin).
- **New audit events**: `PASSKEY_ENROLLED`, `PASSKEY_AUTH_SUCCESS`, `PASSKEY_AUTH_FAILED`, `PASSKEY_REPLAY_REJECTED`, `PASSKEY_REVOKED`, `PASSKEY_ADMIN_REVOKED`, `PASSKEY_ADMIN_RESET_ALL`, `MFA_ADMIN_RESET`.

### Dependencies

- Added: `com.yubico:webauthn-server-core:2.6.0` + transitive `org.bouncycastle:bcprov-jdk18on` (EdDSA support). ~7 MB JAR growth.

### ADRs

- [ADR-16 — Passkeys as sibling to password, not MFA method](docs/adr/ADR-16-passkeys-sibling-to-password.md).
- [ADR-17 — SMTP hard-gate for `password_login_disabled`](docs/adr/ADR-17-smtp-hard-gate-passwordless-tenants.md).

---

## [1.19.3] - 2026-06-29

Polish release — dependency hygiene, OIDC closure of the v1.18.0 deferred items, and a Brand Identity admin-page restructure.

### Added

- **RFC 8707 resource indicator on the `authorization_code` grant.** `/authorize` now accepts repeatable `resource` parameters; validated identifiers are bound to the issued code and propagated through to the access token's `aud` claim and the resulting session. Refresh-token grant honours the session-bound resources by default and accepts optional narrowing per RFC 8707 §3. Closes the v1.18.0 "Deferred" item.
- **RFC 9068 §5 scope narrowing on token issuance (strict mode).** Token requests targeting one or more APIs (via `resource`) are narrowed to the intersection of the requested `scope` and the union of the targeted APIs' declared scopes. Requested scopes outside that set are rejected with `invalid_scope`. Applies to all three grants: `authorization_code`, `refresh_token`, and `client_credentials`.
- **Admin UI — Scopes editor on the API settings page.** Each API in the workspace registry can declare its accepted scopes via a newline-separated textarea. Declared scopes render as inline badges on the API list. Empty scopes column = no narrowing (backwards-compatible for v1.18.0 deployments).
- **`scopes_supported` discovery metadata.** `/.well-known/openid-configuration` now emits the union of the OIDC baseline (`openid profile email`) and every enabled API's declared scopes for the tenant.

### Changed

- **Brand Identity admin page restructured into two cards.** The `/settings/branding` page now renders a top-level **Brand Identity** card (logo, favicon, accent color, support email) above a **Visual Theme** card (theme preset, remaining colors, font, border radius, default locale). The `fromDisplayName` input is removed from the branding form — the SMTP card's existing `smtpFromName` is the operator-managed display name; `emailBranding.fromDisplayName` remains as an API-only override (matches the existing `brandName`/`brandColorHex`/`brandLogoUrl` pattern).
- **Dependencies bumped:** `org.junit.jupiter:junit-jupiter-engine` 5.10.5 → 6.1.0; `org.jetbrains.exposed` group 0.61.0 → 1.3.0 (major package restructuring to `org.jetbrains.exposed.v1.*`); runtime container base `eclipse-temurin:17-jre` → `eclipse-temurin:25-jre`.

### Migrations

- `V53__resource_indicators_auth_code.sql` — adds `scopes JSONB NOT NULL DEFAULT '[]'` to `resource_servers`, and `resources JSONB NOT NULL DEFAULT '[]'` to `authorization_codes` and `sessions`. All defaults preserve v1.19.2 behaviour for existing rows.

---

## [1.19.2] - 2026-06-22

Docker stack consolidation. No runtime changes — image and JAR are unchanged from `1.19.1`. Six compose files become two.

### Changed

- **Two root compose files replace the six in `docker/`.**
  - `docker-compose.yml` (root) — local + eval + `make up` (build via `--build`). Bundled Postgres always, Redis behind `--profile redis`.
  - `docker-compose.prod.yml` (root) — production with Caddy TLS. `KAUTH_TRUSTED_PROXY=true` baked in, same `--profile redis`. Operators wanting a managed database set `DB_URL`; the bundled `db` service can be removed by hand for that case.
- **Comments stripped** from `Dockerfile`, `docker/Caddyfile`, and both compose files. All deployment guidance lives in `docs/deploy/`.
- **`docs/deploy/quickstart.md`** — new, canonical local-evaluation guide.
- **`docs/deploy/production.md`** — new, replaces `docs/guides/production-deployment.md`. Adds a profiles reference, external-database section, file-based-secrets recipe (moved out of the compose file), and a Caddy-vs-own-proxy decision tree.
- **`make up` rebuilds via the root compose file with `--build`.** `make run` now sets `KAUTH_I18N_BUNDLE_DIR=docs/i18n` so contributors editing translations see them after a restart — replaces the bind-mount the deleted `docker-compose.dev.yml` carried.

### Removed

- `docker-compose.quickstart.yml`
- `docker/docker-compose.yml`
- `docker/docker-compose.dev.yml`
- `docker/docker-compose.external-db.yml`
- `docker/docker-compose.prod.yml`
- `docker/docker-compose.demo.yml`
- `docs/guides/production-deployment.md` — superseded by `docs/deploy/production.md`

### Migration

Operators pointing automation at the old paths must update:

- `docker-compose.quickstart.yml` → `docker-compose.yml`
- `docker/docker-compose.yml` → `docker-compose.yml`
- `docker/docker-compose.prod.yml` → `docker-compose.prod.yml`
- External-DB overlay (`docker/docker-compose.external-db.yml`) → set `DB_URL` in `.env`; the bundled `db` service runs idle or can be removed by hand
- Demo overlay (`docker/docker-compose.demo.yml`) → set `KAUTH_DEMO_MODE=true` in `.env`

---

## [1.19.1] - 2026-06-22

Supply-chain hygiene patch. Closes L6 and L7 from the 2026-06-12 security audit.

### Security

- **File-based secret injection (`*_FILE` convention).** `KAUTH_SECRET_KEY`,
  `DB_PASSWORD`, `KAUTH_REDIS_PASSWORD`, `KAUTH_BOOTSTRAP_ADMIN_PASSWORD`, and
  `KAUTH_BOOTSTRAP_API_KEYS` now accept a sibling `<NAME>_FILE` env var pointing
  at a filesystem path. The contents are read and trimmed at startup. Compatible
  with Docker Swarm secrets, Kubernetes mounted secrets, and systemd
  `LoadCredential=`. `<NAME>_FILE` takes precedence when both are set. Working
  example in `docker/docker-compose.prod.yml`.

### Added

- **Gradle dependency locking.** `gradle.lockfile` pins the resolved dependency
  graph across all configurations. CI verifies resolution against the lockfile on
  every PR. Run `make update-locks` after any dependency change to regenerate.
- **Dependabot.** Weekly alerts for Gradle dependencies (Ktor, Exposed, and Kotlin
  grouped to reduce noise), GitHub Actions, and Docker base images.

---

## [1.19.0] - 2026-06-19

Audit integrity + multi-audience introspection. Closes the M9 finding from the
2026-06-12 security audit and the documented v1.18.0 limitation around
multi-resource token introspection.

### Security

- **Audit log details are now built with kotlinx.serialization.** Hand-concatenated
  JSON in `PostgresAuditLogAdapter` was silently corruptible when any detail value
  contained quotes, backslashes, or control characters. Switched to
  `buildJsonObject`; values are escaped correctly and the resulting JSONB always
  round-trips.
- **HMAC chain over the audit log.** Each row carries a `prev_hash` and `row_hash`
  derived from an HMAC-SHA256 keyed by `KAUTH_SECRET_KEY`. Tampering with a row or
  reordering rows breaks the chain and is detected by a new `verify-audit-chain` CLI
  command. Per-tenant chains; first row in a tenant has `prev_hash = NULL`.
- **Recommended Postgres role separation** for `audit_log` documented in
  `docs/operations/audit-log.md` — the app user gets `INSERT, SELECT`; a separate
  maintenance role keeps `UPDATE/DELETE` for legitimate backups and migrations.

### Changed

- **`AccessTokenClaims.aud` widened from `String` to `List<String>`.** Multi-aud tokens
  now decode correctly throughout introspection and any future internal `aud`
  checks. The introspection response (RFC 7662 §2.2) now emits `aud` — a JSON
  string for one audience, a JSON array for two or more.

### Migrations

- `V52__audit_log_chain.sql` adds `prev_hash`, `row_hash`, `chain_key_id` columns to
  `audit_log`. Existing rows keep these `NULL`; new rows always populate them.

---

## [1.18.0] - 2026-06-17

Audience-targeted M2M tokens — RFC 8707 Resource Indicators. Tokens
issued from the `client_credentials` grant can now carry the targeted
API's identifier as the `aud` claim, instead of always the caller's
`client_id`. Resource servers configure one stable audience (their own
identifier) regardless of how many callers exist.

### Added

- **`resource` request parameter** on the token endpoint
  (`client_credentials` grant). Repeatable. Each value is looked up in
  the tenant-scoped API registry and validated against the calling
  client's authorization. Unknown or unauthorized resources produce a
  proper RFC 8707 `invalid_target` 400 — never a silently-broad token.
- **APIs registry** (admin: `/settings/apis`). Workspace-scoped CRUD for
  the audience identifiers M2M tokens can target. Identifier is
  immutable after creation; disable is the primary destructive action;
  hard delete requires typing the audience to confirm.
- **Per-client "Authorized APIs"** screen
  (`/applications/<client>/authorized-apis`) — a checkbox list of every
  registered API in the workspace. Disabled APIs render as a
  non-selectable badge.
- **Discovery metadata** —
  `/.well-known/openid-configuration` advertises
  `"resource_indicators_supported": true` so RFC 8707-aware client
  libraries and API gateways enable the `resource` parameter
  automatically.
- **Audit log** — `TOKEN_ISSUED` events for `client_credentials` now
  include the resolved resources (comma-separated identifiers) in their
  details map.

### Changed

- **`client_credentials` honors the per-client `audience` column when
  no `resource` is sent.** The hierarchy is now
  `resource → audience column → client_id` — same shape as user tokens.
  Resource servers configured to accept the caller's `client_id` keep
  working; deployments that set the `audience` column on an M2M client
  start emitting that value automatically. Verify before upgrading.

### Migrations

- `V51__resource_servers.sql` — adds `resource_servers` and
  `client_authorized_resources`. Empty tables; no backfill; absence of
  rows is legacy behavior.

### Deferred (planned for v1.19+)

- `resource` parameter on the `authorization_code` / user-token path.
- Per-resource scope narrowing (RFC 9068 §5) — issue only the subset of
  scopes the targeted API defines.
- Widening `AccessTokenClaims.aud` to `List<String>` so token
  introspection surfaces every audience on a multi-resource token.
  Issuance is already correct; introspection currently reads only the
  first audience.

---

## [1.17.0] - 2026-06-17

Email i18n + Tier-3 security polish. Closes the polish-tier findings from
the 2026-06-12 audit and ships localized transactional emails. Heavier
Tier-3 items (CSRF, SSRF, audit-integrity, secret rotation, supply-chain)
land in follow-up releases.

### Added

- **Localized transactional emails.** All eight email types (verification,
  password reset, account locked, password changed, SMTP test, invite,
  magic link, OTP) now render in the tenant's `defaultLocale`. ~30 new
  `EMAIL_*` keys live in `EnglishStrings` and ship in `docs/i18n/es.json`.
  Per-key fallback to English for untranslated messages; no `EmailPort`
  signature change.

### Security

- **L8.** Suppress the `Server` response header — Ktor 3.4's default
  reveals the engine and version. Now overridden to empty across all
  responses; `SecurityHeadersTest` locks the behavior.
- **L4.** PKCE S256 challenge comparison uses `MessageDigest.isEqual`
  instead of `==`, eliminating a timing oracle on the verifier check.
- **L1.** `code` and `state` are URL-encoded on the authorization-code
  redirect — closes a query-parameter injection vector when state
  contains reserved characters. Matches the silent-auth path.
- **L2.** `KOTAUTH_MFA_PENDING` cookie now carries `Secure` when the base
  URL is HTTPS — applies to both the set and clear directives.
- **L3.** MFA recovery codes raised from 8-char (32-bit) to 16-char hex
  (64-bit) — the recommended minimum for one-shot fallback tokens.
- **L5.** Admin error page no longer prints the raw exception message or
  qualified class name. Details are logged server-side; the user sees a
  generic apology.
- **M11.** Refuse to start when `KAUTH_DEMO_MODE=true` and
  `KAUTH_ENV=production` — mirrors the H6 quickstart-secret guard.
- **M12.** Update-check hardened: `KAUTH_UPDATE_CHECK_URL` must use
  `https://` (fatal otherwise), the HTTP client follows zero redirects,
  and `releaseUrl` values from the manifest are restricted to `https://`
  schemes (rejects `javascript:`, `data:`, plain `http://`).

---

## [1.16.0] - 2026-06-17

Security hardening release. Closes the seven Tier-2 findings from the
2026-06-12 security review: container hardening, theme input validation,
JWT issuer enforcement, social-link email-verification gate, TOTP replay
+ lockout, post-logout redirect SSRF, and login error normalisation.

### Security

- **Container drops privileges (H7).** Runtime image now runs as a
  non-root user (`kotauth`, UID/GID 10001). All `docker-compose*.yml`
  files set `security_opt: no-new-privileges`, `cap_drop: ALL`,
  `read_only: true`, with `tmpfs: /tmp` for writable scratch space.
- **TenantTheme inputs validated server-side (H5).** New
  `validateTenantTheme` helper enforces strict patterns for colour hex,
  font family, border-radius unit, locale (BCP-47), and logo URL
  schemes. Backup imports go through the same validator.
- **JWT introspection enforces `iss` (M4).**
  `TokenPort.decodeAccessToken` now requires the caller to pass the
  expected issuer; `JwtTokenAdapter` rebuilds the verifier with
  `.withIssuer(...)` so a token minted for tenant A can no longer be
  introspected against tenant B's verifier.
- **Social-link email-verification gate (M1).** The auto-link-by-email
  branch in `SocialLoginService` now rejects with
  `LinkRequiresEmailVerification` when the provider has not verified
  the address. The GitHub adapter only marks
  `SocialUserProfile.emailVerified = true` when the address came from
  `/user/emails` (which exposes `verified: true`), never from the
  public `/user` payload alone.
- **TOTP replay + per-enrollment lockout (M2 / M3).** `TotpUtil.verify`
  now returns the matched time step. `MfaService` rejects any code
  whose matched step is at or before the previously-consumed step
  (replay) and locks the enrollment after
  `MAX_FAILED_TOTP_ATTEMPTS` (5) consecutive failures for the tenant's
  configured `lockoutDurationMinutes`. New audit event
  `MFA_TOTP_LOCKOUT`. Schema: V50 adds `last_used_step`,
  `failed_mfa_attempts`, `mfa_locked_until` to `mfa_enrollments`.
- **Post-logout open-redirect closed (M5).** The OIDC end-session
  endpoint now refuses `post_logout_redirect_uri` values like
  `//evil.com`, `/\evil.com`, `\\evil.com`, and any non-origin
  absolute URI. Only same-origin URIs or rooted relative paths are
  accepted.
- **Login error normalisation + timing equalisation (M6).**
  `AccountLocked`, `PendingSetup`, `PasswordExpired`, and
  `PasswordChangeRequired` all render the same generic
  "Invalid username or password." message. `AuthService` runs a dummy
  bcrypt verify on the user-not-found / disabled / locked /
  pending-setup branches so all four states share the same response
  latency as a wrong-password attempt. The OIDC route no longer
  redirects expired passwords to `forgot-password?reason=expired` —
  that branch was an enumeration vector.

### Migrations

- `V50__mfa_replay_lockout.sql` — adds three columns to
  `mfa_enrollments`. Backwards-compatible: existing rows get
  `last_used_step = NULL`, `failed_mfa_attempts = 0`,
  `mfa_locked_until = NULL`.

---

## [1.15.0] - 2026-06-15

Internal refactor release. **No functional change.** The 2026-06-12
god-file-refactor plan is fully landed, the largest service files are
broken into focused services, and the detekt ratchet is tightened so
new god files can't reappear silently.

### Changed

- **`AdminService` (1145 LOC) deleted** — split into four focused
  services per the plan: `WorkspaceSettingsService`, `AdminUserService`,
  `ApplicationManagementService`, `AdminAccountService` (previously
  proposed as `AdminCredentialService`, renamed per clean-code review).
  Shared `AdminResult` / `AdminError` sealed types live in
  `AdminResult.kt`. Each new service depends only on the repos and ports
  it actually uses
- **`UserSelfServiceService` (1157 LOC) deleted** — split into
  `CredentialFlowService` (token + email flows: email verification,
  forgot password, invite, forced password change, magic link,
  account-locked notification) and `AccountSelfService` (logged-in
  profile + password change + session management). Shared
  `SelfServiceResult` / `SelfServiceError` types live in
  `SelfServiceResult.kt`
- **`WorkspaceSettingsUpdate` parameter object** replaces the
  25-positional-parameter signature on `updateWorkspaceSettings`. The
  factory `WorkspaceSettingsUpdate.from(tenant)` also closed a latent
  bug where the general-settings POST silently reset
  `lockoutMaxAttempts`, `corsAllowCredentials`, magic-link, and OTP
  fields to compile-time defaults when an admin saved the general tab
- **`emailOtpLoginRoutes` cleanup** — `/email-otp/send` and
  `/email-otp/verify` POST handlers extracted into private
  `suspend handleSendOtp` and `handleVerifyOtp` helpers
- **`validatePasswordPolicy` extracted to `domain/util/`** — the
  duplicated private helper that lived in both
  `CredentialFlowService` and `AccountSelfService` is now a single
  top-level function, eliminating drift risk for password-policy
  enforcement
- **Named TTL constants** in `CredentialFlowService`
  (`EMAIL_VERIFICATION_TTL_SECONDS`, `PASSWORD_RESET_TTL_SECONDS`,
  `INVITE_TTL_SECONDS`, `TEMP_PASSWORD_TTL_SECONDS`) replacing bare
  arithmetic like `72 * 3600`

### Tooling

- **Detekt thresholds tightened** — `LargeClass` 600→400,
  `TooManyFunctions` 20 (classes) / 25 (files). The new baseline
  records the surfaces that legitimately exceed the threshold
  (`OAuthService` is deliberately deferred per the plan;
  `ServiceGraph.Companion` is the composition root). New god files
  cannot reappear silently

### Notes

- `OAuthService` is intentionally **not** split in this release — the
  plan defers it until after OIDC certification testing, since protocol
  code benefits from locality and it just absorbed three security fixes

---

## [1.14.1] - 2026-06-12

Closes the published-default admin credential window flagged in the
2026-06-11 security audit.

> **BREAKING CHANGE**
>
> The seeded `admin` user is no longer assigned the documented password
> `changeme123!`. Operators who scripted around that literal must either
> set `KAUTH_BOOTSTRAP_ADMIN_PASSWORD` or capture the random password
> printed once to stdout on first boot. See
> [ENV_REFERENCE.md](docs/ENV_REFERENCE.md#kauth_bootstrap_admin_password).

### Security

- **Default admin credential is gone** — the publicly-documented
  `changeme123!` password is removed from the codebase, image, and docs.
  On a fresh database the seeded `admin` is provisioned from
  `KAUTH_BOOTSTRAP_ADMIN_PASSWORD` if set, otherwise from a 128-bit
  cryptographically random password printed once to stdout in a clearly
  framed banner. The high-entropy ephemeral credential is the rotation
  gate — only the operator who captured the boot log can use it
- **`KAUTH_BOOTSTRAP_ADMIN_PASSWORD` fail-fast validation** — must be at
  least 12 characters with upper, lower, and digit; weak values abort
  startup with a boxed FATAL message rather than silently seeding a poor
  credential
- **Demo mode unchanged** — `KAUTH_DEMO_MODE=true` still provisions a
  fixed, documented demo password (`Demo1234!`, aligned with the
  existing demo-tenant users) so demo deployments stay usable. Demo
  mode is already rejected in production by the quickstart-secret check

### Changed

- Demo banner now displays `admin / Demo1234!` to match the new seeded
  demo credentials
- The `CHANGE_PASSWORD` required action introduced in 1.14.0 is **no
  longer set on the seeded admin**. That flag is paired with an
  admin-issued `TEMP_PASSWORD` token, which the seed path cannot
  produce, so it locked the operator out of their own instance. The
  rotation guarantee is now provided by the unique-per-instance random
  password itself

---

## [1.14.0] - 2026-06-11

Security hardening release implementing Tier 1 of an internal security audit.

> **BREAKING CHANGES**
>
> 1. `/protocol/openid-connect/introspect` and `/revoke` now require
>    confidential-client authentication (Basic auth or `client_id` +
>    `client_secret` form params). Anonymous callers receive `401`.
> 2. Deployments behind a reverse proxy (other than the bundled Caddy
>    overlay, which sets it automatically) must set
>    `KAUTH_TRUSTED_PROXY=true` to keep resolving real client IPs.
> 3. `DB_PASSWORD` is now required — startup fails fast when unset.
> 4. Confidential clients must send `client_secret` on the
>    `refresh_token` grant.

### Security

- **Refresh grant now authenticates confidential clients** — `refresh_token`
  grant requests for confidential clients must include a valid
  `client_secret` (RFC 6749 §6). Previously only the public `client_id` was
  checked, so a leaked refresh token was redeemable without credentials
- **`/introspect` and `/revoke` require client authentication** — both
  endpoints now mandate confidential-client credentials via Basic auth or
  form post (RFC 7662 §2.1, RFC 7009 §2.1) and respond `401 invalid_client`
  otherwise. Revocation is additionally tenant-scoped. **Breaking:** callers
  using these endpoints anonymously must now send client credentials
- **Refresh-token replay detection** — rotated refresh tokens are remembered
  (`sessions.revocation_reason`, V48); presenting one again is treated as
  token theft per the OAuth 2.0 Security BCP: every session for the user is
  revoked and a `refresh_token_replay_detected` audit event is recorded.
  Tokens invalidated by logout do not cascade
- **Forwarded headers are now opt-in** (`KAUTH_TRUSTED_PROXY`, default off) —
  previously `X-Forwarded-For` was always honored, letting clients on
  directly-exposed deployments spoof per-IP rate-limit buckets for login,
  token, MFA, and OTP endpoints. The Caddy production overlay enables it
  automatically. **Breaking:** proxied deployments not using the bundled
  overlay must set `KAUTH_TRUSTED_PROXY=true` to keep real client IPs
- **`DB_PASSWORD` is required** — the silent `"password"` fallback is
  removed; startup fails fast with guidance when unset
- **Quickstart secret key refused in production** — the publicly-committed
  `KAUTH_SECRET_KEY` from `docker-compose.quickstart.yml` aborts startup
  when `KAUTH_ENV=production`
- **Seeded admin must change password on first login** — the default
  `admin` user is created with the `CHANGE_PASSWORD` required action
- **Dev compose binds Postgres/Redis to loopback** — `127.0.0.1:5432` and
  `127.0.0.1:6379` instead of all interfaces

---

## [1.13.0] - 2026-05-26

Hosted Email OTP login. v1.12.0 shipped Email OTP as an admin-API primitive
for partner BFFs. v1.13.0 ships the browser-driven equivalent — a two-step
hosted login page where end users enter their email, receive a 6-digit
code, and sign in. `EmailOtpService` was built consumer-agnostic in v1.12.0;
this release is route + view + session-state.

### Added

- **`/email-otp` and `/email-otp/verify` hosted login pages** — two-step
  flow (enter email → receive 6-digit code → enter code → sign in).
  Single `<input>` with `autocomplete="one-time-code"` triggers native
  iOS/Android SMS autofill on a single field (the 6-box pattern breaks
  paste + autofill on Android). MFA chain reuses
  `completeAuthorizationCodeFlow(mfaCompleted=false)` so TOTP-enrolled
  users still get challenged
- **Per-tenant `emailOtpLoginEnabled` toggle** — new V48 migration adds
  `email_otp_login_enabled` to `tenant_security_config`. Independent of
  `emailOtpSignupEnabled` (which gates the BFF find-or-create path)
- **Login-method picker entry** — when the toggle is on, the hosted login
  page shows a "Sign in with an email code instead" footer link next to
  the existing magic-link link. Both passwordless alternatives are
  visually grouped under a thin top-border separator so the
  forgot-password row stays distinct
- **`KOTAUTH_AUTH_CONTEXT` cookie carries OTP flow state** — extended
  with two new fields (`otpChallengeId`, `otpEmail`) so the code-entry
  page knows which challenge and email to display. Backward-compatible:
  legacy 9-field cookies still parse, the OTP fields are optional
- **Resend code from the verify page** — hidden-form button reuses the
  same `/email-otp/send` endpoint. The service-layer resend invalidates
  the prior challenge (shipped v1.12.0); the route updates the cookie
  with the new challenge ID and flashes "code resent"
- **`EnglishStrings` for the hosted OTP pages** — `EMAIL_OTP_TITLE` +
  subtitle + email/code labels + submit + resend + error copy, plus
  `LOGIN_EMAIL_OTP_LINK` for the picker entry

### Changed

- **Auth Methods card** in Security settings reordered to put the
  login-side toggle before the BFF-side signup toggle (login is the
  primary use case; signup is BFF-only)
- **SMTP-not-configured warning consolidated** — when either OTP toggle
  is enabled and SMTP is missing, a single inline warning renders
  between the two toggles instead of duplicating on each
- **OTP cross-challenge lockout threshold** now disables only when
  BOTH login and signup toggles are off (previously only checked
  signup, which would have left the login flow without a brute-force
  guard — security fix)
- **`OtpVerifyResult.Success`** carries `userId` so the hosted route
  can enter the MFA-aware completion helper directly. Existing admin-API
  consumers ignore the new field

### Notes

- Constant-time padding for the hosted flow is **200ms** (vs **800ms**
  for the BFF). 800ms made the hosted UX feel broken; 200ms still
  defeats timing enumeration. Documented in ADR-15
- No same-browser guard on the OTP consume — that's the failure mode
  OTP solves (corporate scanners prefetching magic-link URLs,
  cross-device email clients). Documented in ADR-15
- **AdminService split** (`WorkspaceSettingsService` / `AdminUserService`
  / `ApplicationManagementService`) was originally planned for this
  release but deferred to a dedicated v1.13.1 polish PR. The 1145-LOC
  service + 24 callers + test redistribution carried unjustified risk
  alongside a flagship feature. The `TODO(v1.13)` comment in
  `AdminService.kt` stays as the marker
- **`EmailTemplatePort` extraction** remains deferred (per ADR-15) —
  `SmtpEmailAdapter` is ~700 lines, still below the 800-line trigger,
  and no second consumer surfaced this release
- 5 new route integration tests (`EmailOtpLoginRoutesTest`) + extended
  backup round-trip assertion for the new field

---

## [1.12.1] - 2026-05-26

Polish release closing the ADR-15 follow-ups, the UX-review deferred items,
and the OTP observability gaps before v1.13.0 hosted-page work begins.
Also fixes a `keyPrefix` overflow bug in the v1.12.0 bootstrap path that
would have failed in Postgres at boot for any tenant slug ≥ 3 chars.

### Fixed

- **`KAUTH_BOOTSTRAP_API_KEYS` default `keyPrefix` overflowed the
  `api_keys.key_prefix` `VARCHAR(16)` column** — the v1.12.0 default
  `"kauth_${slug}_bootstrap"` produced strings up to 20 chars. Failed
  silently in the in-memory fake and only surfaced against Postgres at
  boot. Default now truncates to fit; the fake repository enforces the
  16-char ceiling so the regression class is caught at test time
- **Redirect-URI presence validated at client save** — admins can no
  longer save a client with zero redirect URIs. Previously the surface
  showed up only at use-time as a 422 `invalid_client` from verify-otp.
  ADR-15 follow-up
- **`EMAIL_OTP_SENT` audit detail no longer carries `source=admin_api`** —
  the OTP send path isn't admin-API-exclusive (v1.13 hosted page will
  share the code). A `source=admin_api` filter for compliance would have
  produced false positives

### Added

- **OpenAPI spec covers the OTP endpoints + new scopes** — `/auth/send-otp`
  and `/auth/verify-otp` documented with request/response schemas,
  status codes (202 / 200 / 410 / 422 / 429), and the `auth:send-otp` /
  `auth:verify-otp` scopes. The `/api/docs` Swagger page is now complete
- **"Email OTP" audit-log filter group** — `EMAIL_OTP_*` events get
  their own optgroup in the workspace audit log dropdown, before the
  generic "Email & Password" group. Same first-match-wins pattern v1.11.1
  used for the "Impersonation" group
- **"Recent OTP activity" panel on the admin user-detail page** — mirrors
  the "Recent Impersonations" panel from v1.11.1. Shows last 5
  `EMAIL_OTP_SENT/VERIFIED/REJECTED/LOCKOUT` events for the user
- **SMTP-not-configured signal for OTP** — sends now log a WARN with
  tenant slug + email + exception class when delivery fails. The admin
  Security settings card warns operators when the OTP signup toggle is
  enabled against an SMTP-unready tenant

### Changed

- **Auth Methods card reorders so the magic-link and OTP pairs stay
  grouped** — the passwordless-only toggle moves to the bottom of the
  card. It governs the whole password channel, not magic links
  specifically, so sandwiching it between the two passwordless rows
  was misleading
- **Bootstrap API key rows use an inline info icon with `aria-label`** —
  replaces the title-only tooltip on "Env-managed" that wasn't
  discoverable on keyboard or touch
- **33 `Unnecessary non-null assertion` compiler warnings cleared** across
  `AuthService`, `SocialLoginService`, `*Views`, and the test suite —
  build output is now warning-free except for the unrelated Ktor
  `Principal` deprecation
- **`ServiceGraph` rate-limiter wiring** — 6 nearly-identical
  Redis-or-in-memory blocks collapse to a `buildRateLimiter(max,
  windowSecs, prefix)` factory
- **`EMAIL_OTP_*` audit events get coloured badges** — verified is green,
  rejected and lockout are red. Default neutral was misleading

### Notes

- New regression and coverage tests:
  - `default keyPrefix fits the 16-char column ceiling for any tenant slug`
  - `updateApplication - rejects when redirect URIs is empty`
  - `sendOtp swallows SMTP failure but still records the audit event`
  - `verifyOtp on the stale handle after resend returns InvalidOtp`
  - 4 `updateEmailBranding` validation paths (hex, support email, NotFound,
    persisted sanitised fields)
  - `updateEmailBranding is a soft no-op when the repository is not wired`
  - `import preserves OTP security config fields and email branding`
    (closes the v1.12.0 backup round-trip blind spot)
  - `verify-otp rejects a challenge from a different tenant`
  - Content-Type problem+json header assertion on the wrong-code path
- ADR-15 follow-ups noted in code: PKCE-bypass rationale + scope-derivation
  TODO on `EmailOtpService.issueAuthorizationCodeFor`; future-split TODO on
  `AdminService` (`WorkspaceSettings` / `AdminUser` / `ApplicationManagement`
  earmarked for v1.13)
- Next: **v1.13.0** ships the hosted login-page Email OTP — same
  `EmailOtpService` consumer-agnostic core, plus the auth-page views
  and challenge-id session-state for the browser-driven flow

---

## [1.12.0] - 2026-05-25

Three agnostic IAM primitives driven by the second round of Zion-onboarding
integration needs. Together they unlock email-first passwordless onboarding
for any future Kotauth consumer — not just the BFF that requested them.

### Added

- **Email OTP passwordless primitive** — new admin API endpoints
  `POST /t/{slug}/api/v1/auth/send-otp` and `POST /.../verify-otp`. send-otp
  generates a 6-digit numeric code (SHA-256 stored, 10-minute TTL), sends a
  branded email, and returns an opaque `challengeId`. verify-otp validates
  the code, stamps `email_verified=true`, and returns a single-use
  authorization code the caller exchanges at the standard OIDC `/token`
  endpoint. Per-email + per-IP rate limits via the existing Redis (or
  in-memory) limiter; constant-time response posture at the route layer;
  audit events `EMAIL_OTP_SENT` / `EMAIL_OTP_VERIFIED` / `EMAIL_OTP_REJECTED`
  / `EMAIL_OTP_LOCKOUT`. Use cases beyond onboarding: step-up auth on
  sensitive actions, email-change verification, cross-device login. See
  ADR-15
- **Find-or-create user from send-otp** — per-tenant
  `email_otp_signup_enabled` flag (default off) on Security settings. When
  enabled, send-otp atomically creates a passwordless user if the email
  doesn't already exist. The originating `client_id` flows through to the
  v1.11.0 default-roles grant and the authorization code's bound client,
  so a freshly created user receives the client's default role bundle and
  gets a token with the configured audience. Uniform response shape either
  way — the BFF cannot distinguish new from returning users
- **Cross-challenge OTP lockout defence** — per-tenant
  `email_otp_lockout_threshold` (default 5) trips the existing
  `locked_until` window when a user fails N consecutive challenges. The
  same `EmailPort.sendAccountLockedEmail` template fires with OTP abuse as
  the reason; audit event `EMAIL_OTP_LOCKOUT` carries the threshold and
  duration. Locked users get a uniform `too_many_attempts` response — the
  lockout never leaks to the BFF. Per-challenge attempt cap stays at 5
- **Per-tenant transactional email branding** — new
  `tenant_email_branding` table (1:1 with tenants) carrying `brand_name`,
  `brand_color_hex`, `brand_logo_url`, `support_email`,
  `from_display_name`. Composed into the `Tenant` aggregate so all six
  existing email templates (verification, magic link, password reset,
  account locked, invite, password changed) inherit the branding for free
  — no per-template wiring. New branding editor card on the workspace
  Branding settings page; falls back to tenant defaults when fields are
  blank. The envelope sender (`from_email`) stays operator-controlled for
  DKIM/SPF/DMARC alignment — see ADR-15
- **Boot-time admin API keys via `KAUTH_BOOTSTRAP_API_KEYS`** — JSON
  env var parsed at startup. Idempotent upsert by `(tenant_id, name)` —
  a typo on the tenant slug or scope is a fail-fast process exit, not a
  silent half-startup. Rotating a key = edit the hash in the env var.
  Bootstrapped rows are flagged in the admin UI with a "Bootstrapped"
  badge and an "Env-managed" action column; revoke/delete is refused with
  a 403 to keep the env var as the single source of truth. Closes the
  v1.11.0 deferred item that became blocking when the BFF needed a key
  baked into CI
- **`cli hash-api-key` subcommand + `make generate-api-key TENANT=<slug>`** —
  operator helper that mints a fresh key in the `kauth_<slug>_<random>`
  format and prints the plaintext (capture once) and the SHA-256 (paste
  into the env var). `--key=<plaintext>` mode hashes a supplied value
- **New API scopes** — `auth:send-otp` and `auth:verify-otp` added to the
  `ApiScope.ALL` catalog. Keys can be scoped narrowly to the OTP surface
- **ADR-15 — email-OTP passwordless primitive** — documents the
  find-or-create posture, originating-client plumbing, constant-time
  approach, cross-challenge lockout, `from_email` DKIM lock, and
  `EmailTemplatePort` deferral

### Changed

- **Email adapter resolves branding through one shared layout function** —
  `buildEmailHtml` in `SmtpEmailAdapter` now reads `tenant.emailBranding`
  for brand name, color, logo, and support-email footer. From display name
  resolution chain is `tenant.emailBranding?.fromDisplayName ?:
  tenant.smtpFromName ?: tenant.displayName` — tenants who previously
  customized only the SMTP `from_name` keep their existing behavior
- **`grantClientDefaultRoles` extracted to a shared helper** — the
  four-liner in `AuthService` and `SocialLoginService` (introduced by
  v1.11.0) is now a top-level `applyClientDefaultRolesGrant` in
  `domain/service/`. The new `EmailOtpService` uses the same helper so
  all three registration paths grant the same default-role bundle for
  the same originating client

### Notes

- Hosted login-page Email OTP is intentionally out of scope for v1.12.0
  and tracked for v1.13.0. `EmailOtpService` was designed consumer-agnostic
  so the hosted page wires in without re-plumbing
- The `originatingClientId` plumbing requires the BFF's confidential client
  to have at least one redirect URI registered. A missing redirect URI
  makes verify-otp return 422 `invalid_client`. Operator configuration
  requirement, documented in ADR-15
- V45 migration creates `email_otp_challenges`, adds
  `email_otp_signup_enabled` and `email_otp_lockout_threshold` columns to
  `tenant_security_config`, and adds `failed_otp_challenges` to `users`.
  V46 creates `tenant_email_branding`. V47 adds `(tenant_id, name) UNIQUE`
  and `bootstrap_name` to `api_keys`
- Backup/restore round-trips the new `SecurityConfig` fields and the
  `tenant_email_branding` row. Old backups still import — all new fields
  have safe defaults
- 25 new tests (13 service, 6 OTP-route integration, 6 bootstrap-service)

---

## [1.11.1] - 2026-05-22

Polish release. Closes long-deferred small items, two impersonation
discoverability fixes promised in v1.10.0, a real bug in the backup format,
and one developer-experience improvement.

### Added

- **Per-tenant magic-link token TTL** — magic-link expiry was a hardcoded 15
  minutes; tenants can now configure it in workspace Security settings (range
  1–1440 minutes, default 15). The `MAGIC_LINK_REQUESTED` audit event also now
  records the TTL that was applied, so post-incident analysis can correlate
  "expired before user clicked" reports against the configured window. V44
  Flyway migration adds the column; one constant read in
  `UserSelfServiceService.initiateMagicLink` becomes a per-tenant lookup. Was
  feature-backlog item #9, started as a free-rider in V39 and reverted
- **Impersonation events have their own audit-log filter group** — the
  workspace audit log dropdown gains an "Impersonation" optgroup before
  "Admin Actions". Filtering to impersonation-only is now a one-click action
  instead of scrolling a dropdown of 30 ADMIN_* events
- **"Recent Impersonations" panel on the admin user-detail page** — when a
  user has been impersonated, the page renders a compact table of the last
  5 impersonations of that user (admin username + timestamp), pulled from
  the audit log. Empty when none — no noise on regular users. For operator
  incident response: "was this user impersonated, and by whom?" no longer
  requires opening the audit log and filtering by hand
- **`admin_username` in `ADMIN_IMPERSONATION_STARTED` audit details** — the
  event already carried the admin's user id; the username is now stored
  alongside so consumers (the new panel, log scrapers, future tooling) do not
  need a cross-tenant user lookup to render attribution
- **ADR-14 — admin impersonation session model** — documents the
  parallel-sessions + RFC 8693 `act` claim + cascade-revocation choices made
  in v1.10.0, and the alternatives we rejected
- **`make run` target — fast inner loop without full Docker rebuilds** —
  starts only `db` + `redis` in Docker and runs the JAR locally via
  `./gradlew run` with safe dev-only env vars. Avoids the multi-minute Docker
  rebuild on every Kotlin change. The existing `make up` (full Docker stack,
  build from source) is unchanged and remains the right target for verifying
  the production container before pushing

### Changed

- **"Impersonate user" button is rendered disabled (with tooltip)** instead
  of being hidden when the target user is disabled, locked, or has a pending
  invite. Discoverability matters — an admin investigating a problem user
  used to wonder where the action went; now they see the button with an
  explanation of why it is unavailable
- **Magic-link admin-settings copy** — the help text on the existing "Allow
  sign-in via email magic link" toggle no longer hardcodes "15 minutes";
  it points to the new TTL field below

### Fixed

- **Backup/restore loses `passwordLoginEnabled`** — a tenant configured as
  passwordless (v1.10.0+) would silently come back as password-enabled after
  a restore, because `SecurityConfigBackup` was missing the field. Now
  included; old backups still import (the field has a default of `true`,
  which matches the historical behavior). The new `magicLinkTokenTtlMinutes`
  field is also included with a default of 15
- **`MAGIC_LINK_TTL_SECONDS` constant removed** — it had been bypassed by the
  per-tenant lookup but the stale 15-minute value was still readable from
  the class

### Notes

- 4 new tests covering the per-tenant TTL behavior (honors configured value,
  defaults to 15, records on audit, coerces 1..1440 at the service layer)
- **`detekt` was evaluated and explicitly deferred** — ktlint covers
  formatting; detekt's complexity findings would largely confirm what
  `wc -l` already shows on the large service classes, and the baseline-file
  maintenance cost is not yet earned by the codebase. Reconsider when the
  team is actively splitting service classes or adopts coroutines broadly
- **Docker compose layout was reviewed and judged sound** — six files looks
  like a lot but each has a distinct purpose (quickstart, base, dev,
  external-db, demo overlay, prod TLS overlay). The quickstart file was
  given a maintainer-note comment about drift from the base stack; a deeper
  refactor via `include:` is possible but the merge semantics are subtle and
  the current overlay design is the right pattern

---

## [1.11.0] - 2026-05-22

### Added

#### Client default roles

- **Per-application default roles granted at self-registration** — an admin can configure a set of roles that are assigned automatically to any user who self-registers through a given OAuth application. When a user completes registration via an application's `/authorize` flow, every role associated with that application is granted to the new user, so their very first token already carries the intended roles. Eliminates the common integration friction where a Backend-for-Frontend has to detect the missing role after login, call the admin API to assign it, and force a token refresh — and the tenant-wide admin API key that pattern required. Standard IAM capability, modelled on Keycloak's "default roles" and Auth0's post-registration Actions
- **Grant-at-registration semantics** — default roles are granted when the user account is created through a self-registration flow, attributed to the originating OAuth client. A returning user who later registers their first OAuth client through a different application does **not** retroactively receive that client's defaults — silently gaining roles by signing into a new app is a privilege-escalation surprise. Default roles are also **not** applied to admin-created or invite-accepted users — an admin creating a user already has full role control. Applies to both the password-registration path (`AuthService.register`) and the social-registration path (`SocialLoginService.completeSocialRegistration`) identically
- **V42 `client_default_roles` table** — a clean `(client_id, role_id)` join table, both columns `ON DELETE CASCADE`. The composite primary key doubles as the lookup index. New `RoleRepository.findDefaultRolesForClient` / `setDefaultRolesForClient` (atomic full-set replace) port methods, implemented for both Postgres and the in-memory test fake
- **Admin UI — "Registration Defaults" card** on the application detail page (`/admin/workspaces/{slug}/applications/{clientId}`) — lists the configured default roles in a table with per-row removal, plus a dropdown + "Add Role" control. The dropdown is server-filtered to only the roles that are valid here (tenant-scoped roles and this application's own client-scoped roles), so an invalid selection is never offered. Mirrors the existing "Composite Children" card pattern exactly
- **Admin API — `GET` and `PUT /t/{slug}/api/v1/applications/{appId}/default-roles`** — read the configured set, or replace it atomically with a `{ "roleIds": [...] }` body. `GET` is scoped to `applications:read`, `PUT` to `applications:write`
- **Role-assignment admin API accepts a role name** — `POST`/`DELETE /t/{slug}/api/v1/users/{userId}/roles/{roleRef}` now resolves `{roleRef}` as either a numeric role id (unchanged) or a role name. Role ids are environment-specific `SERIAL` values unknown at build time; addressing a role by its stable name removes a per-environment lookup or config value for integrators. A name that is ambiguous across scopes (the same name used as both a tenant-scoped and a client-scoped role) is rejected with a clear message pointing back to the numeric id

#### Custom token audience

- **Per-application token audience** — an application can now declare the `aud` claim its issued JWTs carry, instead of `aud` always equalling the `client_id`. Lets one OAuth client mint tokens for a resource server whose identifier differs from the client_id, without overloading `client_id` itself or registering a throwaway client named after the audience. V43 migration adds a nullable `audience` column to `clients`; `JwtTokenAdapter` resolves the access-token audience as `audience → client_id → tenant slug`. Configurable via a "Token Audience" field on the application edit page and the `audience` field on the application admin API. This is deliberately a single configurable value, not RFC 8707 Resource Indicators — the column solves the real multi-resource-server case at minimal cost
- **`Application.audience` field** — surfaced on the application admin API DTO and threaded through `ApplicationRepository.update` and `AdminService.updateApplication` (rejects values over 200 characters)

### Changed

- **`AuthService` and `SocialLoginService`** gained optional `applicationRepository` + `roleRepository` constructor dependencies and an `originatingClientId` parameter on their registration methods. The originating `client_id` is threaded from the register route via the OAuth auth-context cookie and from the social-registration route via the pending-registration cookie's stored OAuth parameters. Defaults preserve the prior behavior — no originating client means no default roles
- **`RoleGroupService`** gained `getClientDefaultRoles`, `setClientDefaultRoles`, and `resolveRole` (numeric-id-or-name resolution). `setClientDefaultRoles` records an `ADMIN_CLIENT_UPDATED` audit event
- **`ServiceGraph`** wires the new repository dependencies into `AuthService` and `SocialLoginService`; `adminApplicationRoutes` and `apiApplicationRoutes` now receive `RoleGroupService`

### Security

- **Cross-client privilege-escalation guard** — `RoleGroupService.setClientDefaultRoles` rejects associating a client-scoped role that belongs to a *different* application as a default for this one, and rejects roles from another tenant. Without this guard the `client_default_roles` table would let a compromised or careless admin hand out another application's roles to every new user. The admin-console dropdown is also pre-filtered so an invalid role is never even presented
- **Default roles are independent of the email-verification gate** — roles are granted at account creation regardless of whether the tenant requires email verification. A role is a claim about who the user is; the `email_verified` token claim is a separate gate enforced by the resource server. Resource servers that require verified email continue to enforce it on the token, unaffected

### Notes

- Both features are generic, integrator-agnostic IAM capabilities. They were prompted by an internal onboarding-BFF integration but nothing integration-specific appears in the schema, domain model, or API
- 30 new tests — repository-contract tests for the default-roles store, `RoleGroupService` tests for the scope guard and name resolution, registration-grant tests across the password and social paths, `JwtTokenAdapter` audience-resolution tests, and admin-API integration tests including the cross-client rejection

---

## [1.10.0] - 2026-04-30

### Added

#### App launcher

- **Per-workspace app launcher** at `/t/{slug}/launcher` — a new top-level portal page that surfaces every application a user is entitled to as a tile grid (icon + name), each opening in a new tab. Sits alongside the account section in the portal shell with a new "Applications" entry at the top of the sidebar/tabnav. Empty state copy ("Ask your workspace admin to grant you access to one or more applications.") gives users a clear next step instead of a dead end. Designed as the SSO landing page for organizations running multiple apps on Kotauth — operators can point employees at a single URL and let role membership drive what they see
- **`LauncherService.resolveLauncherApps(userId, tenantId)`** — pure domain service that filters every application in the tenant by `enabled ∧ launcherVisible ∧ launcherUrl != blank` and applies the entitlement rule below. Sorts by `launcherDisplayOrder` ascending, then by app name ascending (case-insensitive). Reuses existing client-scoped roles + `RoleRepository.resolveEffectiveRoles` (which already expands group membership and composite roles), so the launcher inherits every entitlement primitive without a parallel ACL system
- **Entitlement model — open by default, locked by client roles** — an application with zero `RoleScope.CLIENT` roles defined is visible to every user in the tenant (the common case for shared landing pages and team-wide tools). The moment an admin defines one or more client-scoped roles on the application, visibility flips to opt-in: a user must hold at least one of those roles (directly, via a group, or via a composite role expansion) to see the tile. Tenant-scoped roles never grant launcher visibility on their own — preventing "admin" from accidentally implying "can see every app"
- **Launcher fields on `Application`** — `launcherUrl: String?` (the public URL the tile navigates to; `null` omits the app from the launcher), `iconUrl: String?` (optional, may be served from a different origin like a CDN; falls back to the app's first letter when blank or unreachable), `launcherVisible: Boolean = true` (admin-controlled soft hide that preserves `launcherUrl`), `launcherDisplayOrder: Int = 0` (lower numbers appear first; ties broken by name). V40 Flyway migration adds the four columns to the `clients` table; persisted via `PostgresApplicationRepository` and round-tripped through the existing `ApplicationRepository` port
- **Origin validation on `launcherUrl`** — `AdminService.updateApplication` rejects launcher URLs whose origin (`scheme://host[:port]`) does not match any of the application's already-registered redirect URIs. Mitigates a phishing surface where a compromised admin could point a tile at an attacker-controlled host. Both `launcherUrl` and `iconUrl` must be valid `http`/`https`; non-http schemes (`javascript:`, `data:`, etc.) are rejected. `iconUrl` is allowed cross-origin so CDN-hosted icons work
- **Admin UI — per-application "Launcher" card** on `/admin/workspaces/{slug}/applications/{clientId}/edit` — four fields (launcher URL, icon URL, "Show in launcher" toggle, display order 0–9999) with hints explaining the origin-match rule and the tie-break behavior. The application detail page surfaces a read-only summary card showing the configured URL, icon URL or "Using letter fallback", a Visible/Hidden badge, and the display order; an empty state with a CTA appears when no launcher URL is set
- **Broken-icon onerror fallback** — every tile renders the icon as `<img>` with an inline `onerror` handler that swaps to a `<span>` containing the app's first uppercase letter (taken from `data-fallback`). Survives DNS failures, CDN outages, and 404s without leaving an alt-text-only box on the page
- **`launcher.css` portal module** — new responsive grid (`.launcher-grid`, `.launcher-tile`, `.launcher-tile__icon`, `.launcher-tile__name`, `.launcher-empty`) imported into both `index-portal-sidenav.css` and `index-portal-tabnav.css` so the launcher renders consistently across both portal layout variants
- **27 new tests** — 8 in `AdminServiceTest` covering origin-match positive, mismatched-origin rejection, non-http scheme rejection, no-redirect-URIs rejection, blank-clears-value, CDN icon allowed cross-origin. 12 in `LauncherServiceTest` covering open access, restricted hidden/visible, disabled/invisible/null/blank-URL exclusions, cross-tenant isolation, sort-by-displayOrder-then-name, tenant-scoped role does NOT grant launcher access, composite-role expansion grants access, mixed catalog returns the right per-user view, empty tenant. 5 view-render tests in `LauncherViewRenderTest` (empty state, tile attributes, icon onerror fallback, no-icon initial, active-nav highlighting). 2 route integration tests in `LauncherRoutesTest` (auth gate redirects to portal login, 404 on unknown tenant)

#### Admin impersonation (RFC 8693)

- **One-click "Impersonate user"** on `/admin/workspaces/{slug}/users/{userId}` — primary action button alongside the existing user-detail actions. Confirmation modal explains the consequence ("You will be signed into the portal as this user. All actions are recorded in the audit log.") so the action never feels stealthy. Restricted to enabled, unlocked, fully-activated users in non-master workspaces; admins cannot impersonate themselves or other platform admins. Uses the existing `data-confirm` modal pattern, not browser `confirm()`
- **Two parallel sessions, not a swap** — the admin's `AdminSession` cookie (path-scoped to `/admin`) stays intact while a new `PortalSession` cookie is set for the impersonated user (path-scoped to `/`). The admin can still navigate the admin shell in another tab. When impersonation ends, only the portal cookie is cleared — the admin remains signed in. Avoids the orphan-state problem of swap-style impersonation when the admin session expires mid-flow
- **Persistent warning-orange banner** above the portal shell on every page during impersonation — full-width, sticky, `--color-amber` toned. Lead reads "Impersonating {target}", subtext "Signed in as {admin} — actions are recorded", and an "End session" button posts to `/t/{slug}/account/impersonation/stop`. Designed per UX review to be impossible to miss without being alarming (orange = non-standard mode, not red = something broke). Positioned above the topbar/sidebar so it is the first element on the page
- **Server-refused destructive self-service controls during impersonation** — the portal hides the delete-account confirm block and disables the change-password submit button (with an "Unavailable during impersonation" tooltip) when `PortalSession.isImpersonation` is true. The matching POST handlers (`/account/delete`, `/account/change-password`) return 403 if invoked under impersonation. Belt-and-suspenders: the UI prevents accidents, the server prevents bypass
- **RFC 8693 `act` claim on issued tokens** — both the access token and the id_token carry a nested `act: { sub: <admin-user-id> }` claim during impersonation, with the JWT `sub` set to the impersonated user. Downstream APIs that verify Kotauth tokens can now distinguish "alice clicked submit" from "admin acting as alice clicked submit" without an out-of-band signal. `JwtTokenAdapter.decodeAccessToken()` surfaces the parsed `act.sub` on `AccessTokenClaims.actingSubject` for downstream consumers
- **Cascade revocation across the session graph** — when the admin's own session row is revoked (logout, "revoke all my sessions", admin-on-admin sweep), every active impersonation child whose `impersonator_session_id` matches is revoked in the same operation. Implemented inside `SessionRepository.revoke()` and `revokeAllForUser()` for all three storage backends (Postgres, Redis, in-memory fake). Closes the dangling-privilege gap where an admin's logout would otherwise leave their impersonation tokens valid until expiry
- **Atomic replace semantics for second-impersonation-while-active** — if the admin clicks "Impersonate Bob" while still impersonating Alice, the prior child session is revoked before the new one is issued. No stacking, no orphans, no "which user am I?" confusion. The admin only ever has at most one active impersonation child per admin session
- **`ADMIN_IMPERSONATION_STARTED` and `ADMIN_IMPERSONATION_ENDED` audit events** — emitted on every start/stop with `details` carrying `target_user_id`, `target_username`, `admin_session_id`, and `impersonation_session_id`. Pairs with the `act` claim on tokens so a complete trail of "who did what as whom" is recoverable from a single tenant's audit log
- **V41 Flyway migration** — adds `impersonator_session_id INTEGER REFERENCES sessions(id) ON DELETE CASCADE` to the `sessions` table plus a partial index `WHERE impersonator_session_id IS NOT NULL` for the cascade query. The FK protects against orphaned children if the parent row is ever physically deleted by the cleanup job; logical revocation lives at the application layer
- **22 new tests** — 13 in `ImpersonationServiceTest` (start success, target not found, master-tenant target rejected, cross-tenant target rejected, disabled-user rejected, locked-user rejected, revoked admin session rejected, audit event details, stop success, stop with mismatched admin session rejected, stop is non-idempotent, cascade-revoke when parent revoked, cascade via revokeAllForUser). 5 in `JwtTokenAdapterActClaimTest` (act absent for normal tokens, act on access token under impersonation, act on id token under impersonation, decode surfaces actingSubject, decode returns null actingSubject for normal tokens). 3 in `ImpersonationBannerRenderTest` (banner absent without impersonation, banner present with admin/target metadata + correct stop URL, banner renders on both layouts). 1 added to `SessionCodecTest` (Redis JSON round-trip preserves `impersonatorSessionId`). Plus 2 in `RedisSessionRepositoryIntegrationTest` (find/revoke by impersonator)

#### Per-tenant "Require passwordless sign-in" toggle

- **Per-tenant "Require passwordless sign-in" toggle** — operators can now disable password authentication entirely on a workspace and run it on email magic-links + social providers only. Surfaces in the workspace Security settings under a new "Authentication Methods" card alongside the existing magic-link toggle. When enabled, the public-facing login page hides the password form and replaces it with an email-only magic-link request (auto-focused), drops the "or continue with" divider so social providers become co-equal primaries, and removes the forgot-password and "sign in with email link" footer links (no longer applicable). The registration page hides the password + confirm-password fields and the divider above them; on successful submit the server issues a magic-link instead of redirecting to a password login page, so the new user can complete first sign-in via email. Magic-link must be enabled before password sign-in can be disabled (the admin form blocks the unsafe combo with a typed `AdminError.Validation` and an actionable message). Master tenant cannot be flipped passwordless — it controls admin console access and would have unbounded blast radius
- **New `securityConfig.passwordLoginEnabled` field on `Tenant`** — defaults to `true`. V39 Flyway migration adds `password_login_enabled BOOLEAN NOT NULL DEFAULT TRUE` to `tenant_security_config`. Persisted via the existing `TenantSecurityConfigTable` insert/update/read paths; no new repository contract
- **`AuthError.PasswordLoginDisabled`** — typed error returned by `AuthService.authenticate()` and `AuthService.register()` whenever a password-bearing call hits a passwordless tenant. Mapped to a user-facing message that points the user at the email-link option or a configured social provider, never leaking that policy enforcement happened. Mirrored in `UserSelfServiceService` as `SelfServiceError.PasswordLoginDisabled` and gates the four other password-bearing self-service paths (`confirmPasswordReset`, `changePassword`, `confirmAcceptInvite`, `confirmForcedPasswordChange`) so a stale tab or scripted client cannot bypass the toggle
- **`AuditEventType.LOGIN_REJECTED_POLICY` and `ADMIN_SECURITY_CONFIG_UPDATED` audit events** — `LOGIN_REJECTED_POLICY` fires whenever an authentication attempt is rejected by tenant policy (currently passwordless-blocking-password) with `details["reason"]="password_login_disabled"` so post-incident analysis can distinguish policy denials from credential failures. `ADMIN_SECURITY_CONFIG_UPDATED` fires only when the toggle actually changes value, capturing the actor admin and the new state — quiet during no-op saves
- **Master tenant carve-out at the service layer** — `AdminService.updateWorkspaceSettings` rejects `passwordLoginEnabled=false` on `Tenant.MASTER_SLUG` with `AdminError.Validation`. The admin UI also renders the toggle as `disabled` on the master workspace as a UX hint, but the server is authoritative
- **14 new domain-service tests** — 3 in `AuthServiceTest` (passwordless tenant blocks login, register, and emits `LOGIN_REJECTED_POLICY`), 4 in `UserSelfServiceServiceTest` (each password-bearing self-service path rejects with `PasswordLoginDisabled`), 7 in `AdminServiceTest` (master carve-out, lockout-protection combo, audit event firing, etc.). 2 new HTTP-integration tests in `AuthRoutesTest` (login page renders email-only form when passwordless; registration creates a user, sends a magic-link, redirects to `/magic-link?sent=true`)

### Changed

- **`Application` domain model + `ApplicationRepository.update` signature** — gained four launcher fields (`launcherUrl`, `iconUrl`, `launcherVisible`, `launcherDisplayOrder`) with safe defaults. Defaults are placed on the interface so existing call sites compile without modification; the admin edit-form route always sends all four fields
- **`PortalView` fully migrated to `ViewContext`** — every public page function (`loginPage`, `profilePage`, `securityPage`, `mfaPage`, plus the new `launcherPage`) now takes a single `ctx: ViewContext` instead of separate `theme: TenantTheme` + `workspaceName: String` parameters. The shell helpers (`portalShell`, `portalShellSidenav`, `portalShellTabnav`, `portalNavItems`, `portalSignOutButton`) thread the ctx through and use `ctx.t(KEY)` for every label. `portalRoutes` and `launcherRoutes` accept `translationPort: TranslationPort` and build a per-request `ViewContext` via the now-public `resolveLocale` helper
- **`AuthService.register()` accepts an empty password when the tenant is passwordless** — the server mints a synthetic 256-bit password (two concatenated UUIDs) to satisfy the `User.passwordHash` schema constraint, skips password-policy validation, skips the confirm-password check, and skips password-history recording (no real password to remember). The user never sees or types this value; their only path to sign in is the magic-link the route layer immediately issues post-registration
- **`registerRoutes(...)` signature** — gained `selfServiceService: UserSelfServiceService` so the success path can call `initiateMagicLink` after a passwordless registration. Passwordless success now redirects to `/t/{slug}/magic-link?sent=true` (the existing enumeration-safe "check your email" page); password-mode success keeps the prior redirect to `/account/login` or `/authorize?registered=true`
- **`AuthView.loginPage(...)` and `AuthView.registerPage(...)` accept `passwordLoginEnabled: Boolean = true`** — every call site in `OAuthProtocolRoutes` (6) and `SocialLoginRoutes` (6) for login, plus all 3 in `RegisterRoutes`, threads the flag through. Nullable-tenant call sites use `tenant?.securityConfig?.passwordLoginEnabled != false` (defaults `true` on null)
- **`AdminService.updateWorkspaceSettings` parameter list** — gained `passwordLoginEnabled: Boolean = true`. The admin form sends `requirePasswordless` (inverted polarity in HTML — checked = passwordless = `passwordLoginEnabled=false`); `AdminSettingsRoutes` translates with `passwordLoginEnabled = params["requirePasswordless"] != "true"`
- **`ServiceGraph`** — gained `launcherService: LauncherService` field. Constructed once at startup and threaded into the new `launcherRoutes` block in `Application.kt`
- **`ServiceGraph` gains `impersonationService: ImpersonationService`** — wired from `userRepository`, `tenantRepository`, `sessionRepository`, the JWT adapter, and `auditLogAdapter`. Threaded into `adminRoutes` (for the start endpoint) and `portalRoutes` (for the stop endpoint, registered alongside `/account/logout`)
- **`TokenPort.issueUserTokens()`** — gained an optional `actingSubject: UserId? = null` parameter. Default null preserves the prior token shape for every existing call site (4 places across `AuthService`, `OAuthService`, `SocialLoginService`); only `ImpersonationService.startImpersonation` passes a non-null value. `JwtTokenAdapter` stamps the nested `act` claim only when this parameter is non-null
- **`SessionRepository.revoke()` and `revokeAllForUser()` now cascade** — both methods, in all three implementations (Postgres, Redis, Fake), additionally revoke any active impersonation children whose `impersonator_session_id` matches the affected parent session. Behavior change is invisible until impersonation is actually used (no children exist on pre-v1.10 deployments at upgrade time). The `SessionRepository` port gained two new methods: `findActiveByImpersonator(parentSessionId)` and `revokeAllByImpersonator(parentSessionId, revokedAt)` — used internally by the cascade and externally by the admin "Impersonate" route to enforce replace-semantics
- **`PortalSession` cookie + `ViewContext`** — the cookie gains three optional impersonation fields (`impersonatorAdminUserId`, `impersonatorAdminUsername`, `impersonatorAdminSessionId`) that are non-null together. `ViewContext` gains an optional `impersonation: ImpersonationContext?` field threaded by both `portalRoutes` and `launcherRoutes`. The view layer renders the banner from this context — no per-page plumbing

### Security

- **Launcher origin validation prevents tile hijacking** — `launcher_url` must share its origin with one of the application's already-registered redirect URIs. A compromised admin who flipped a tile to point at `https://attacker.example.com` would be rejected at the service layer with a typed `AdminError.Validation`. Both `launcher_url` and `icon_url` are required to be `http(s)`; `javascript:`/`data:` schemes are rejected outright
- **Tile clicks open in a new tab with `rel="noopener noreferrer"`** — the new tab cannot reach back to the launcher's `window.opener` and the target page sees no referer header. Standard hardening for any portal-style entry point that links out to third-party origins
- **Enforced at every credential-bearing surface** — passwordless toggle is checked server-side at every entry point that consumes a password: interactive login, registration, password reset confirm, in-portal password change, invite-accept (which sets a password), and forced password change. The login page UI changes are a UX nicety; the server is authoritative. A scripted client cannot enable password authentication on a tenant by skipping the form
- **Email-method lockout protection** — `AdminService.updateWorkspaceSettings` rejects the combo `passwordLoginEnabled=false` AND `magicLinkEnabled=false` with `AdminError.Validation` and an actionable message ("At least one email-based sign-in method must remain enabled. Enable magic links before disabling password sign-in"). Applies on both the API and the admin UI form path. Social-only sign-in is intentionally not enough to disable password — a misconfigured social provider could otherwise lock all users out
- **Audit attribution on policy changes** — `ADMIN_SECURITY_CONFIG_UPDATED` only fires when the toggle's value actually changes, so the audit log captures the moment of policy transition with no noise from no-op saves. Useful for compliance and post-incident review
- **Impersonation can never target a master-tenant user** — `ImpersonationService.startImpersonation` rejects any target user whose tenant is the master tenant before issuing tokens or creating a session row. Closes the admin-on-admin escalation surface where one platform admin could grab another's privileges silently
- **Impersonation cannot bypass admin-session lifecycle** — the cascade revocation built into `SessionRepository.revoke()` and `revokeAllForUser()` means impersonation children never outlive the admin session that spawned them. An admin who logs out, has their session revoked by another admin, or hits `revokeAllForUser` from any flow will have all of their impersonation children revoked atomically in the same operation
- **Sign-side bypass surface for self-service destructives is closed at the server** — `/t/{slug}/account/delete` and `/t/{slug}/account/change-password` return 403 when invoked under impersonation. The UI also disables/hides the buttons (per UX review), but a scripted client cannot reach the action even by skipping the form

### Internationalization

- **All 11 auth pages now use `ViewContext` and `ctx.t()`** — completes the migration started in v1.7.2 (which migrated 5 pages: `loginPage`, `forgotPasswordPage`, `resetPasswordPage`, `acceptInvitePage`, `mfaChallengePage`). This release migrates the remaining 6: `registerPage`, `magicLinkPage`, `magicLinkErrorPage`, `forceChangePasswordPage`, `verifyEmailPage`, `socialRegistrationPage`. Zero hardcoded `+"..."` English strings remain in `AuthView.kt`. Operators with a volume-mounted bundle (`KAUTH_I18N_BUNDLE_DIR`) can now translate the entire user-facing auth surface
- **Portal pages fully migrated to `ViewContext`** — same treatment for `PortalView.kt`. Login, profile, security, MFA, and the new launcher page all read every visible label from the translator, including the shared shell elements (sidebar/topbar nav, sign-out button, confirm dialog, danger-zone copy). 70+ portal/launcher i18n keys added to `EnglishStrings`. With this and the auth migration, every user-facing surface that's not the admin console is now translatable end-to-end
- **115+ new i18n keys this release** — `AUTH_PAGE_TITLE_REGISTER`, `AUTH_PAGE_TITLE_MAGIC_LINK`, `AUTH_PAGE_TITLE_FORCE_CHANGE`, `AUTH_PAGE_TITLE_VERIFY_EMAIL` for page chrome; `LOGIN_PASSWORDLESS_*` (4 keys) for the login page in passwordless mode; `REGISTER_*` (13 keys); `MAGIC_LINK_*` (8 keys); `FORCE_CHANGE_*` (6 keys); `VERIFY_EMAIL_*` (3 keys); `SOCIAL_REG_*` (6 keys); `AUTH_METHODS_*` (5 keys) for the new admin Security card; `PORTAL_LOGIN_*`, `PORTAL_NAV_*`, `PORTAL_PROFILE_*`, `PORTAL_SECURITY_*`, `PORTAL_MFA_*`, `PORTAL_DANGER_*`, `PORTAL_DELETE_*`, `PORTAL_CONFIRM_*` (70+ keys) for the portal pages; `LAUNCHER_*` (6 keys) for the launcher. All defined as `const val String` in `EnglishStrings`, picked up automatically by `EnglishStrings.byKey` via reflection — no per-key registration needed
- **`docs/i18n/es.json` extended to 174 total keys** — every key referenced by the migrated auth + portal + launcher pages now has a Spanish translation. Admin console strings remain English-only fallback (out of scope for this release; tracked for a follow-up sweep)
- **Impersonation banner copy is fully translatable** — five new keys (`IMPERSONATION_BANNER_LEAD`, `IMPERSONATION_BANNER_SIGNED_IN_AS`, `IMPERSONATION_BANNER_AUDITED`, `IMPERSONATION_BANNER_END`, `IMPERSONATION_DISABLED_TOOLTIP`) plus the admin-side `IMPERSONATE_BUTTON`, `IMPERSONATE_CONFIRM`, `IMPERSONATE_REPLACE_CONFIRM`, `IMPERSONATE_FAILED_*`. All have Spanish translations in `docs/i18n/es.json`

### Documentation

- **Per-tenant magic-link token TTL** — tracked as a future follow-up. Started as a free-rider in V39, then reverted because adding the column without wiring it into the magic-link service would leave dead state

---

## [1.9.0] - 2026-04-30

### Added

- **Tenant backup and restore** — encrypted, portable export of a complete workspace to a single `.json.enc` file, importable as a brand-new tenant on any Kotauth deployment. Designed for staging-environment cloning, deployment migration, and disaster-recovery drills. The export bundle contains: tenant config, security policy, branding, theme, identity providers, OAuth applications (with redirect URIs, allowed origins, claim mappers), users (with password hashes, MFA enrollment metadata, social identity links, attributes, required actions), roles + group hierarchy + assignments, webhooks, RSA signing keys (opt-in), and the audit log (opt-in). Single round-trip `BackupExportV1` JSON shape; sealed `BackupResult<T>` for service errors; sealed `BackupError` hierarchy distinguishing `SlugConflict`, `SchemaTooNew`, `WrongPassphrase`, `MalformedEnvelope`, `InvalidPayload`, `UnsupportedExportVersion`, `TenantNotFound`, `Internal`. New-tenant import only — overwriting an existing tenant is intentionally not supported in v1.9.0
- **CLI subcommands** — `kauth cli export-tenant <slug> --passphrase-env=VAR --out=path [--include-signing-keys] [--include-audit-log]` and `kauth cli import-tenant <file> --passphrase-env=VAR --new-slug=<slug>`. Passphrase is read from the named environment variable, never from argv (avoiding shell-history leakage); 16-char minimum enforced; `--out` refuses to overwrite an existing file. Both commands surface the typed `BackupError` with operator-friendly remediation hints. Dispatched in `Application.kt` via the existing `cli/` subcommand pattern
- **Admin API** — `POST /admin/api/v1/tenants/{slug}/export` and `POST /admin/api/v1/tenants/import`. Master-tenant API key auth via `Authorization: Bearer kauth_…`. Two new bearer scopes — `TENANTS_EXPORT`, `TENANTS_IMPORT` — must be granted on the API key. Request/response shapes are MCP-friendly JSON (envelope as base64-encoded string in the body); the export endpoint streams the encrypted envelope back inline rather than as a file download for programmatic consumers. 10 new route integration tests
- **Admin UI — per-workspace export** at `/admin/workspaces/{slug}/settings/backup`. Single form with passphrase + confirm passphrase, optional include checkboxes (RSA signing keys default OFF, audit log default OFF), and a type-the-workspace-slug-to-confirm gate. Server-rendered `kotlinx.html`, follows existing `.ov-card` / `.edit-row` / `.btn--primary` conventions. Browser starts the file download on submit; success toast fires client-side
- **Admin UI — top-level import** at `/admin/workspaces/import`. Multipart form: file picker for the encrypted envelope, passphrase, and a new slug. On success, redirects to the imported workspace's detail page with a flash toast. On any failure, the typed `BackupError` message is rendered above the form so the operator can correct the input without losing what they typed. Two surfaced entry points: a secondary "Import from backup" button on the workspaces list page header, and a "Restoring from a backup? Import instead." link in the create-workspace footer
- **`BackupEncryptionPort` + `Pbkdf2AesGcmBackupEncryption`** — PBKDF2-HMAC-SHA256 with 600,000 iterations (OWASP 2024 minimum) derives a 256-bit key from the operator's passphrase and a 16-byte salt. AES-256-GCM with a 12-byte IV provides authenticated encryption. Envelope format: `bkp1.<salt>.<iv>.<ciphertext+tag>` (all components base64-url-encoded). The `bkp1` prefix is a forward-compatibility version marker; future formats can use `bkp2.…` and the importer surfaces `UnsupportedVersion` for envelopes from a newer build. Decrypt failures cleanly distinguish `WrongPassphrase` from `MalformedEnvelope` from `UnsupportedVersion`, so the UI can render actionable error messages
- **`ADMIN_TENANT_EXPORTED` and `ADMIN_TENANT_IMPORTED` audit events** — fired from both the CLI path and the admin API. Captures the actor (admin user id or API key id), tenant slug, and `details["includeSigningKeys"]` / `details["includeAuditLog"]` for export, `details["newSlug"]` for import. Raw envelope contents never appear in the audit log
- **Schema-version compatibility check** — `FlywaySchemaHead` reads the maximum applied Flyway version at startup; the importer rejects bundles whose `schemaVersion` is newer than the current build's head with `BackupError.SchemaTooNew`. Older-version bundles are accepted (forward migrations are immutable so a v1.7.0 export cleanly imports into a v1.9.0 deployment). Bundle metadata also records `kotauthVersion` for human inspection
- **`.notice--info` CSS modifier and `info.svg` icon** — new informational banner variant alongside `.notice--error`, uses the existing `--color-accent` brand-blue tokens. Used on both the export page (top-of-form scope summary: "Some secrets will not be included…") and the import page (full per-secret recovery list, where it's actionable). Includes `.notice__body ul` styling so structured lists render correctly inside the banner
- **`backup-slug-validation.js`** — progressive-enhancement client-side gate that disables the page-header "Export & download" button until the type-to-confirm slug input matches exactly. Mirrors the existing `password-validation.js` data-attribute pattern (`data-confirm-slug`, `data-confirm-target`). HTML5 `pattern` + `required` remain the authoritative fallback when JS is disabled
- **`window.kotauthToast(message, durationMs?)`** — `toast.js` now exposes a global function and listens for `data-toast-on-submit` on forms. Used by the export form because the file-download response causes no page navigation, so the standard `?flash=…` server-rendered toast cannot fire. Also available for any future client-side success surface
- **34 new tests** — 19 domain-service tests in `BackupExportImportTest.kt` (round-trip, slug conflict, schema-too-new, partial includes, wrong-passphrase, malformed envelope, version mismatch, master-tenant rejection), 10 admin route integration tests in `AdminBackupRoutesTest.kt` (auth, scopes, error mapping), 5 encryption tests in `Pbkdf2AesGcmBackupEncryptionTest.kt` (round-trip, wrong passphrase, tampered ciphertext, malformed envelope, version marker). **920 tests total, 0 failures**

### Changed

- **`adminRoutes(...)` signature** — gained `backupExporterService: BackupExporterService? = null`, `backupImporterService: BackupImporterService? = null`, `backupEncryptionPort: BackupEncryptionPort? = null`, `flywaySchemaVersion: Int = 0`. Defaults are nullable so existing route integration tests compile without modification; backup routes are only mounted when all three services are wired (matches the existing `apiKeyService`/`webhookService` convention)
- **`ServiceGraph`** — gained `backupExporterService`, `backupImporterService`, `backupEncryptionPort`, `auditLogPort`, and `flywaySchemaVersion` fields. The schema-version field is computed once at startup via the new `FlywaySchemaHead` infrastructure so the value is consistent across the request lifecycle
- **`ApiKeyScope`** — added `TENANTS_EXPORT` and `TENANTS_IMPORT`. Existing API keys without these scopes are rejected with 403; admins must explicitly mint a new key for backup operations

### Security

- **Redacted on export, never recoverable from the bundle** — OAuth client secrets, social provider (Google/GitHub) client secrets, SMTP password, MFA TOTP seeds, MFA recovery codes, active sessions, authorization codes, magic-link tokens, password reset tokens. The exported user record retains the bcrypt hash so existing passwords still work after import; secrets that protect outgoing trust (signing the JWT, talking to Google) must be regenerated by the operator post-import. The import-page recovery banner enumerates each secret with the precise location to reconfigure it
- **Passphrase never persisted** — Kotauth does not store the passphrase anywhere, neither during export nor in the audit log. A 16-char minimum is enforced both in the CLI and the admin form. The CLI reads the passphrase from a named environment variable to avoid shell-history leakage; the admin form uses `autocomplete="new-password"` and `type="password"`. The `.notice--info` banner copy makes the responsibility explicit: "Kotauth never stores this passphrase — keep it somewhere safe"
- **All-or-nothing import** — `BackupImporterService.import` runs inside an `ExposedTransactionRunner` transaction. Any failure rolls back the entire tenant; partial-state corruption is impossible. The transaction boundary is at the service layer so the same guarantee holds for both the CLI and the admin API paths
- **Master tenant cannot be exported or imported** — `BackupExporterService.export` rejects `tenant.slug == Tenant.MASTER_SLUG` with `BackupError.TenantNotFound`-style failure to avoid leaking the master tenant name. The importer rejects target slug `master` outright. Cloning the master tenant (which controls admin console access) is intentionally out of scope and would have unbounded blast radius
- **Bundle is opaque without the passphrase** — the encrypted envelope reveals nothing about the source tenant, schema version, included options, user count, or kotauth version. Plaintext metadata (`schemaVersion`, `kotauthVersion`, `exportedAt`) lives inside the encrypted payload, not on the envelope. This is intentional: a leaked envelope without its passphrase yields zero structured information, only ciphertext bytes

### Documentation

- **ADR-12 — Tenant backup and restore** (planned for follow-up commit). Captures the encryption choice (PBKDF2 + AES-GCM), the `bkp1.` envelope versioning, the redaction list rationale, the new-tenant-only import constraint, the master-tenant carve-out, and the schema-version forward-compatibility approach

---

## [1.8.1] - 2026-04-29

### Added

- **OIDC silent SSO across clients on the same tenant** — every successful interactive login now drops a signed `KOTAUTH_SSO` witness cookie path-scoped to `/t/{slug}` (HttpOnly, signed via `EncryptionService.signCookie`, payload `v1|userId|tenantId|authTime|mfaCompleted|expiresAt`). Subsequent visits to `/t/{slug}/authorize` silent-auth by issuing an authorization code without rendering UI. Closes the gap that previously made users land on the portal login screen after signing into a SaaS app. See [ADR-13](docs/adr/ADR-13-oidc-sso-witness-cookie.md)
- **OIDC `prompt` parameter — full §3.1.2.1 support** — `prompt=none` silent-auths or returns `error=login_required`; `prompt=login`/`consent`/`select_account` clear the SSO cookie and force re-auth. Unknown values and the invalid `none + other` combination return `invalid_request`. Discovery doc advertises `prompt_values_supported`
- **`auth_time` claim on every ID token** — `JwtTokenAdapter.issueUserTokens` now writes the OIDC `auth_time` claim (epoch seconds) on the ID token. For MFA logins the value is the moment the **second factor was verified** (Keycloak convention), not the first-factor time. Threaded end-to-end: login → `setSsoCookie` → `AuthorizationCode.auth_time` (V38 migration) → ID token. Discovery doc adds `auth_time` to `claims_supported`
- **OIDC `max_age` parameter** — silent auth refuses to issue when `(now - cookie.authTime) > max_age`. `max_age=0` is treated as the OIDC sentinel meaning "force fresh credential proof now" and always falls through to interactive
- **OIDC `id_token_hint` parameter** — best-effort `sub` extraction from the hint payload; mismatch with the cookie's `userId` blocks silent auth and falls through to interactive. Signature verification of the hint is a v1.9.x follow-up
- **Portal silent SSO via `prompt=none`** — `GET /t/{slug}/account/login` builds the OAuth authUrl with `&prompt=none` on first attempt. `/callback` handles the `error=login_required` response by redirecting to `/login?prompt_failed=true` to break the loop and render the form. Two server hops worst case for first-time visitors; zero form pages for users with a valid SSO cookie
- **`OAuthService.validateRedirectUri(tenantSlug, clientId, redirectUri)`** — new domain method used by GET `/authorize` to validate the redirect_uri against the client's registered list **before** constructing any redirect string. Open-redirect protection for the `prompt=none` failure path
- **Two new env vars** — `KAUTH_SSO_SESSION_TTL_SECONDS` (default 86400 / 24h) and `KAUTH_SSO_SESSION_MAX_TTL_SECONDS` (default 2592000 / 30d). Validated at startup (`60s ≤ ttl ≤ maxTtl`). Documented in [ENV_REFERENCE.md](docs/ENV_REFERENCE.md)
- **V38 migration** — `auth_time TIMESTAMPTZ NULL` column on `authorization_codes`. Nullable for backwards compatibility with codes minted before this column existed; new issuance always populates it
- **Phase 1–5 integration tests** — 4 SSO cookie tests (`SsoCookieTest`), 7 prompt parameter tests (`PromptParamTest`), 13 silent auth tests (`SilentAuthTest`), 2 OIDC end-session tests (`SsoLogoutTest`), 4 portal silent SSO tests (additions to `PortalRoutesTest`). All in default `make test`, no Docker required

### Changed

- **`completeAuthorizationCodeFlow` signature extended** — now accepts `tenantId`, `authTime`, `mfaCompleted`, `ssoTtlSeconds`, `secure`, `encryptionService`. Every call site (password login, MFA challenge, magic-link consume, social login) was updated; `SocialLoginRoutes` was refactored from two inline `issueAuthorizationCode` blocks into a single use of the helper, removing the divergence in code-issuance behavior across credential types
- **`OAuthService.issueAuthorizationCode` accepts `authTime: Instant = Instant.now()`** — silent auth passes the cookie's `authTime`; interactive paths pass the moment of credential verification. The auth code persists this value for the token-exchange path to read
- **`TokenPort.issueUserTokens` accepts `authTime: Instant?`** — null preserves pre-feature token shape (refresh-token paths). When set, `JwtTokenAdapter` writes the `auth_time` claim onto the ID token
- **All three logout paths clear `KOTAUTH_SSO`** — `GET /t/{slug}/protocol/openid-connect/logout`, the corresponding `POST`, and portal `POST /t/{slug}/account/logout`. Without this, logging out then visiting `/account/login` would silent-auth the user straight back in

### Documentation

- **ADR-13 — OIDC silent SSO via path-scoped witness cookie.** Captures the cookie design (path-scope, HMAC-not-JWT, version prefix), `auth_time` plumbing through three layers, the Keycloak-style MFA-completion-time decision, the `mfaCompleted` policy enforcement model, prompt/max_age/id_token_hint semantics, the open-redirect mitigation, threat model, and known limitations (signature-verification of `id_token_hint`, role-aware `required_admins` silent auth, session-bound revocation — all tracked for v1.9.x / v1.10.0)

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
- **Dependency upgrade plan rewritten** — deep migration impact analysis captured for the v2.0 framework upgrade: Ktor 3.x (2.5 days, 4 intercept→plugin conversions + 30 mechanical changes), Exposed 1.0 (1.5 days, limit/offset + TransactionManager accessor), Flyway 11 (30 min). Total: ~4.25 days. Migrations are independent and ordered: Flyway → Exposed → Ktor

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
