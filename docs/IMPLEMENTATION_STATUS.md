# KotAuth — Implementation Status

> Last updated: 2026-03
> Current version: 1.0.0-dev (Phase 3a complete)

This document is the living record of what has been built, what compiles and runs, what is intentionally deferred, and what the next milestone requires. It supplements the strategic `ROADMAP.md`.

---

## Build Health

| Check | Status |
|---|---|
| Docker build (`gradle buildFatJar`) | ✅ Passing |
| PostgreSQL schema (Flyway V1–V8) | ✅ Applied |
| Admin console routes | ✅ All registered |
| OIDC discovery + JWKS | ✅ Functional |
| Authorization Code + PKCE flow | ✅ Functional |
| Client Credentials flow | ✅ Functional |
| Refresh token rotation | ✅ Functional |

---

## Phase Status

### Phase 0 — Foundation ✅ Complete

All production-readiness blockers resolved before Phase 1 work began.

| Feature | Status | Notes |
|---|---|---|
| Rate limiting on `/login` and `/register` | ✅ Done | `RateLimiter` (sliding window, in-memory, per-IP) — 5 login / 1 min, 3 register / 5 min |
| Startup validation of insecure JWT_SECRET in production | ✅ Done | `exitProcess(1)` if `KAUTH_ENV=production` and secret is default value |
| `KAUTH_BASE_URL` env var enforcement | ✅ Done | Fails fast at startup if not set |
| Input trimming and server-side validation | ✅ Done | All form handlers trim and validate before persistence |
| Refresh token persistence | ✅ Done | `sessions` table stores SHA-256 hash; rotation on every use |
| Secure cookie flags | ✅ Done | `HttpOnly` on admin session cookie |
| CSRF on admin logout | ✅ Done | POST-only logout form |
| Email verification flow | ❌ Deferred to Phase 3b | Admin-created users are pre-verified; public registration marks unverified |
| Password reset flow | ❌ Deferred to Phase 3b | Requires SMTP (not yet wired) |
| HTTPS enforcement / startup warning | ❌ Deferred to Phase 5 | TODO comment left in `Application.kt` |

---

### Phase 1 — Multi-Tenancy Core ✅ Complete

| Feature | Status | Notes |
|---|---|---|
| Flyway versioned migrations (V1–V8) | ✅ Done | Replaces `SchemaUtils`; production-safe |
| `tenants` table + master tenant bootstrapping | ✅ Done | Master tenant seeded by `V2__seed_master_tenant.sql` |
| `clients` table with `token_expiry_override` | ✅ Done | Per-client TTL override, nullable (inherits from tenant if null) |
| `redirect_uris` table (separate from clients) | ✅ Done | One-to-many, replaced atomically on client update |
| `users` scoped to `tenant_id` | ✅ Done | All queries include tenant scope; cross-tenant isolation enforced |
| Per-tenant RSA key pair for RS256 | ✅ Done | `TenantKeys` table; auto-provisioned on startup by `KeyProvisioningService` |
| Tenant CRUD in admin console | ✅ Done | Create / view / settings edit |
| Client CRUD in admin console | ✅ Done | Create / view / edit / enable-disable / secret regeneration |
| Basic user list per tenant | ✅ Done | Extended in Phase 3a |
| Admin console shell (rail + ctx-panel + top bar) | ✅ Done | Workspace switcher dropdown, rail nav, context panel |
| Admin session auth (cookie-based, 8h TTL) | ✅ Done | `AdminSession` Ktor session; auth guard via `ApplicationCallPipeline.Call` intercept |

---

### Phase 2 — OAuth 2.0 / OIDC Compliance ✅ Complete

All standard endpoints implemented. Any OIDC-compliant client library will work against KotAuth.

#### Endpoints

| Endpoint | Method | Status | Notes |
|---|---|---|---|
| `/.well-known/openid-configuration` | GET | ✅ Done | Full discovery document |
| `/protocol/openid-connect/certs` | GET | ✅ Done | JWKS with RSA public key |
| `/protocol/openid-connect/auth` | GET | ✅ Done | Authorization Code + PKCE, shows login/consent |
| `/protocol/openid-connect/token` | POST | ✅ Done | `authorization_code`, `client_credentials`, `refresh_token` grants |
| `/protocol/openid-connect/userinfo` | GET | ✅ Done | Returns standard OIDC claims for bearer token |
| `/protocol/openid-connect/logout` | POST | ✅ Done | Revokes session; supports `post_logout_redirect_uri` |
| `/protocol/openid-connect/revoke` | POST | ✅ Done | RFC 7009 token revocation |
| `/protocol/openid-connect/introspect` | POST | ✅ Done | RFC 7662 token introspection |

#### Token & Security

| Feature | Status | Notes |
|---|---|---|
| RS256 signing (asymmetric) | ✅ Done | Per-tenant key pairs; clients verify without calling KotAuth |
| Standard OIDC claims (`sub`, `iss`, `aud`, `exp`, `iat`, `jti`, `email`, `email_verified`, `name`, `preferred_username`) | ✅ Done | |
| PKCE (`S256` code challenge method) | ✅ Done | Required for public clients |
| Refresh token rotation with absolute expiry | ✅ Done | Each refresh creates a new session, revokes the old one |
| `token_expiry_override` per client | ✅ Done | Falls back to tenant default if null |
| Session persistence (`sessions` table) | ✅ Done | SHA-256 hash of access + refresh tokens |
| Audit log on all auth events | ✅ Done | `audit_log` table; append-only via `AuditLogPort`. `AuthService` records `LOGIN_SUCCESS`/`LOGIN_FAILED` for all login paths (direct, OAuth, admin console). |

#### Compile-error fixes (end of Phase 2)

The following bugs were identified and fixed before Phase 3a work began:

| Bug | File | Fix |
|---|---|---|
| `Unresolved reference: tokenExpiryOverride` | `JwtTokenAdapter.kt`, `OAuthService.kt` | Added `val tokenExpiryOverride: Int? = null` to `Application` domain model and `toApplication()` mapper |
| `Unresolved reference: origin` (wrong Ktor 2.x API) | `AuthRoutes.kt` (6 locations) | Replaced all `call.request.origin` → `call.request.local` (`RequestConnectionPoint`) |
| Cascade errors `scheme`, `serverHost`, `serverPort` | `AuthRoutes.kt` | Resolved by same fix above |

---

### Phase 3a — Admin Console (Complete) ✅ Complete

This phase delivers a fully functional admin console. Every page renders real data and all mutations go through the domain service layer.

#### Domain Layer

| Component | File | What it does |
|---|---|---|
| `AdminService` | `domain/service/AdminService.kt` | Orchestrates all admin mutations: workspace settings, user CRUD, application updates, secret rotation, session revocation. Returns `AdminResult<T>` (Success/Failure discriminated union). |
| `AdminResult<T>` / `AdminError` | same file | Sealed result types — `NotFound`, `Conflict`, `Validation`. Routes map these to appropriate HTTP status codes without leaking exceptions. |
| New `AuditEventType` variants | `domain/model/AuditEvent.kt` | `ADMIN_TENANT_UPDATED`, `ADMIN_CLIENT_UPDATED`, `ADMIN_CLIENT_SECRET_REGENERATED`, `ADMIN_CLIENT_ENABLED`, `ADMIN_CLIENT_DISABLED`, `ADMIN_USER_CREATED`, `ADMIN_USER_UPDATED`, `ADMIN_USER_ENABLED`, `ADMIN_SESSION_REVOKED` |

#### Port Extensions

| Port | New methods |
|---|---|
| `TenantRepository` | `findById(id: Int): Tenant?`, `update(tenant: Tenant): Tenant` |
| `UserRepository` | `findByTenantId(tenantId: Int, search: String?): List<User>`, `update(user: User): User` |
| `ApplicationRepository` | `update(appId, name, description, accessType, redirectUris): Application`, `setEnabled(appId, enabled)` |
| `SessionRepository` | `findActiveByTenant(tenantId: Int): List<Session>` |
| `AuditLogRepository` *(new)* | `findByTenant(tenantId, eventType?, userId?, limit, offset): List<AuditEvent>`, `countByTenant(...): Long` — read-only port, separate from write-only `AuditLogPort` |

#### Adapter Implementations

| Adapter | What was added |
|---|---|
| `PostgresTenantRepository` | `findById`, `update` (all mutable fields) |
| `PostgresUserRepository` | `findByTenantId` with optional LIKE search across username/email/fullName; `update` |
| `PostgresApplicationRepository` | `update` (atomic redirect URI replace via `deleteWhere` + `batchInsert`); `setEnabled` |
| `PostgresSessionRepository` | `findActiveByTenant` (non-revoked, non-expired, ordered by `createdAt DESC`) |
| `PostgresAuditLogRepository` *(new)* | Read-only query adapter implementing `AuditLogRepository` |

**Bug fixed:** `PostgresAuditLogRepository` line 55 — `OffsetDateTime.toInstant()` takes no arguments (the offset is embedded in the value). Incorrect call `toInstant(ZoneOffset.UTC)` was removed. This was the sole compile error blocking the Phase 3a Docker build.

**Bug fixed (post-3a):** `AuthService` had no `AuditLogPort` dependency — login events (`LOGIN_SUCCESS`, `LOGIN_FAILED`) were never recorded, making the audit log page appear empty. Fixed by adding `auditLog: AuditLogPort` to `AuthService` and recording events in `authenticate()`. All call sites (`AuthRoutes`, `AdminRoutes`) updated to pass `ipAddress` and `userAgent`. `AdminRoutes` admin login now calls `authenticate()` instead of `login()` so master-tenant logins are audited too.

#### Admin Routes

All routes registered under `/admin/workspaces/{slug}/`:

| Route | Method | Handler |
|---|---|---|
| `/settings` | GET | Show workspace settings form |
| `/settings` | POST | Update workspace settings via `AdminService` |
| `/applications/{clientId}/edit` | GET | Show edit application form |
| `/applications/{clientId}/edit` | POST | Update application via `AdminService` |
| `/applications/{clientId}/toggle` | POST | Enable/disable application |
| `/applications/{clientId}/regenerate-secret` | POST | Regenerate client secret; new secret shown once via `?newSecret=` query param |
| `/users` | GET | User list with optional `?q=` search |
| `/users/new` | GET | Show create user form |
| `/users` | POST | Create user via `AdminService` (admin-created = pre-verified) |
| `/users/{userId}` | GET | User detail with inline edit form and active sessions |
| `/users/{userId}/toggle` | POST | Enable/disable user |
| `/users/{userId}/edit` | POST | Update user profile via `AdminService` |
| `/users/{userId}/revoke-sessions` | POST | Revoke all user sessions |
| `/sessions` | GET | Workspace-wide active session list |
| `/sessions/{sessionId}/revoke` | POST | Revoke one session |
| `/logs` | GET | Audit log with event-type filter and pagination (50/page) |

#### Admin Views

New page functions added to `AdminView.kt`:

| Function | Page |
|---|---|
| `workspaceSettingsPage` | Settings form: identity, token TTLs, password policy, branding |
| `editApplicationPage` | Edit form with locked client ID, access type, redirect URIs |
| `userListPage` | Searchable user table with active/disabled status badges |
| `createUserPage` | Create user form (username / email / full name / password) |
| `userDetailPage` | Profile edit form + active sessions table + enable/disable + revoke-all header actions |
| `activeSessionsPage` | Workspace-wide session table with per-row revoke |
| `auditLogPage` | Paginated event log with event-type dropdown filter |

`applicationDetailPage` updated to accept `newSecret: String?` — renders a one-time banner prompting the admin to copy the new client secret.

New data class: `UserPrefill` — holds create-user form values for re-population after a failed submission (mirrors `WorkspacePrefill` and `ApplicationPrefill`).

---

### Phase 3b — User Self-Service & Email Flows ❌ Not started

The next milestone. Blocked on SMTP configuration.

Key deliverables:
- Email verification flow (token via email, verified flag set on click)
- Password reset / forgot password (time-limited reset link)
- User self-service portal: view/edit own profile, see own sessions, change password
- SMTP configuration per tenant (stored in `tenants` table — column not yet added)
- TOTP / MFA enrollment (Phase 3c or 4 depending on prioritization)

---

### Phase 4 — Identity Federation ❌ Not started

Social login (Google, GitHub, generic OIDC) and SAML 2.0. No groundwork laid yet. Will require a new `identity_providers` table and significant OAuth client flow work.

---

### Phase 5 — Developer Experience ❌ Not started

Admin REST API, webhooks, SDK/integration guides, Prometheus metrics, structured JSON logging.

---

## Architecture Decisions (Recorded)

### ADR-01: Hexagonal (Ports & Adapters) Architecture

**Decision:** All domain logic in `domain/` has zero framework dependencies. Adapters in `adapter/` implement domain ports. The composition root (`Application.kt`) wires everything.

**Consequence:** Adding a new persistence layer (e.g., MySQL) means writing a new adapter, not touching domain logic. Tested domain services with no Ktor/Exposed imports.

### ADR-02: Flyway for Schema Migrations

**Decision:** Replaced `SchemaUtils.createMissingTablesAndColumns` with Flyway V1–V8 migrations before Phase 1.

**Consequence:** Schema changes are versioned, reversible, and safe to run in CI/CD. No schema drift between dev and production.

### ADR-03: Audit Log Split — Write Port vs Read Repository

**Decision:** `AuditLogPort` (write-only, fire-and-forget, implemented by `PostgresAuditLogAdapter`) is separate from `AuditLogRepository` (read-only, paginated queries, implemented by `PostgresAuditLogRepository`).

**Consequence:** Auth-path audit writes never block on query logic. Admin console reads never share a code path with hot auth flows.

### ADR-04: Admin-Mutating Operations via `AdminService`, Not Direct Repositories

**Decision:** All admin write operations (create user, update workspace, regenerate secret, etc.) go through `AdminService` rather than calling repositories directly from routes.

**Consequence:** Every mutation records an audit event in the same call. Validation and business rules live in one place. Routes stay thin.

### ADR-05: Client Secret — Raw Value Never Stored

**Decision:** `regenerateClientSecret` generates a 32-byte CSPRNG secret, stores only its bcrypt hash, and returns the raw value once. The raw value is passed via a URL query parameter `?newSecret=...` and shown once in the admin UI with a "copy now" warning.

**Consequence:** Even a full database dump reveals no usable client secrets. The trade-off is that a lost secret requires regeneration (not recovery).

### ADR-06: `AdminResult<T>` Instead of Throwing Exceptions

**Decision:** `AdminService` methods return `AdminResult.Success<T>` or `AdminResult.Failure(AdminError)`. Routes pattern-match on the result.

**Consequence:** Error handling is explicit and exhaustive. No try/catch in routes. `AdminError` subtypes (`NotFound`, `Conflict`, `Validation`) map cleanly to HTTP 404/409/422.

---

## Known Deferred Items (Not Bugs — Intentional Scope)

| Item | Where it appears | Why deferred |
|---|---|---|
| `cookie.secure = true` | `Application.kt` line ~163, TODO comment | Requires TLS termination — Phase 5 |
| Email verification for public registration | `AuthService.register()` | Requires SMTP — Phase 3b |
| Password reset | n/a | Requires SMTP — Phase 3b |
| TOTP / MFA | n/a | Phase 3b/4 |
| Roles & groups | n/a | Phase 3b |
| Bulk user operations | n/a | Phase 3b |
| User impersonation | n/a | Phase 3b, with strict audit logging |
| Session revocation on password change | n/a | Phase 3b |
| Concurrent session limits | n/a | Phase 3b |
| Admin REST API | n/a | Phase 5 |
| Prometheus metrics | n/a | Phase 5 |
| Structured JSON logs | n/a | Phase 5 |
| LDAP / SAML | n/a | Phase 4 |

---

## File Index — New Files Added

| File | Phase | Purpose |
|---|---|---|
| `domain/port/AuditLogRepository.kt` | 3a | Read-side port for audit log queries |
| `domain/service/AdminService.kt` | 3a | Admin mutation orchestration with `AdminResult<T>` return types |
| `adapter/persistence/PostgresAuditLogRepository.kt` | 3a | Implements `AuditLogRepository`; paginated audit log reads |

## File Index — Significantly Modified

| File | Phase | What changed |
|---|---|---|
| `domain/model/Application.kt` | 2 fix | Added `tokenExpiryOverride: Int?` |
| `domain/model/AuditEvent.kt` | 3a | Added 9 admin-specific `AuditEventType` variants |
| `domain/port/TenantRepository.kt` | 3a | Added `findById`, `update` |
| `domain/port/UserRepository.kt` | 3a | Added `findByTenantId` (with search), `update` |
| `domain/port/ApplicationRepository.kt` | 3a | Added `update`, `setEnabled` |
| `domain/port/SessionRepository.kt` | 3a | Added `findActiveByTenant` |
| `adapter/persistence/PostgresTenantRepository.kt` | 3a | Implemented new port methods |
| `adapter/persistence/PostgresUserRepository.kt` | 3a | Implemented new port methods (LIKE search) |
| `adapter/persistence/PostgresApplicationRepository.kt` | 2 fix + 3a | Added `tokenExpiryOverride` mapping; implemented `update`, `setEnabled` |
| `adapter/persistence/PostgresSessionRepository.kt` | 3a | Implemented `findActiveByTenant` |
| `adapter/persistence/PostgresAuditLogAdapter.kt` | existing | Unchanged — write path only |
| `adapter/web/admin/AdminRoutes.kt` | 3a | Complete rewrite — all Phase 3a routes |
| `adapter/web/auth/AuthRoutes.kt` | 2 fix | `call.request.origin` → `call.request.local` (6 locations) |
| `adapter/web/admin/AdminView.kt` | 3a | 966 → 1843 lines; 7 new page functions, `applicationDetailPage` updated, `UserPrefill` added |
| `Application.kt` | 3a | Wired `AdminService`, `PostgresAuditLogRepository`; updated `module()` signature and `adminRoutes()` call |
