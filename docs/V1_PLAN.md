# KotAuth V1 Implementation Plan

**Status:** In Progress — Phase 4 complete, Phase 5 (Documentation) next
**Started:** 2026-03-16
**Target:** First public release

---

## Overview

This document tracks the work required to ship KotAuth V1 — from correctness fixes through
social login, admin REST API, webhooks, and documentation. Phases 0–3d are complete and
production-ready. This plan covers the remaining work to reach a releasable first version.

---

## Phase 1 — Correctness Fixes

> Pre-requisite for everything else. These are bugs in existing functionality, not new features.

### 1.1 Fix `resource_access` JWT claim keys

**Problem:** Client-scoped roles in the `resource_access` JWT claim use the internal integer
primary key (e.g., `"42"`) instead of the human-readable `client_id` string (e.g., `"my-app"`).
Any client library decoding the token receives `"42"` instead of the expected identifier.

**Fix:** In `JwtTokenAdapter.issueUserTokens`, when building `resourceAccess`, check if the
`client: Application?` parameter matches the role's `clientId` integer FK. If so, use
`client.clientId` (the string). Falls back to the integer-as-string for edge cases.

**File:** `adapter/token/JwtTokenAdapter.kt`

---

### 1.2 Implement `required_admins` MFA policy enforcement

**Problem:** `MfaService.isMfaRequired()` returns `false` for the `required_admins` policy
(line 300, hardcoded `false` with a TODO comment). Admin users can log in without MFA even
when the tenant policy requires it for admins. The feature is visible and configurable in the
UI but silently does nothing at runtime.

**Fix:**
1. Update `isMfaRequired(user, policy, userRoles: List<Role> = emptyList())` to check
   `userRoles.any { it.name == "admin" }` for the `required_admins` case.
2. Inject `RoleRepository` into `authRoutes()` (nullable for backward compat).
3. In the login POST handler, after auth success, resolve effective roles when policy is
   `required_admins` and call `isMfaRequired` with them.
4. If MFA is required but the user hasn't enrolled, block login with an actionable error message
   directing them to the user portal for MFA setup.

**Files:** `domain/service/MfaService.kt`, `adapter/web/auth/AuthRoutes.kt`

---

### 1.3 Make `login()` self-contained for password expiry

**Problem:** `AuthService.authenticate()` checks password expiry (lines 101–118), but
`AuthService.login()` does not. The browser login path works because routes call `authenticate()`
before `login()`. However `login()` is not standalone-correct — any future caller that uses
`login()` directly bypasses the expiry check. This is a latent bug.

**Fix:** Add the same expiry check block to `login()` immediately after password verification
(before issuing tokens), mirroring the logic in `authenticate()`.

**File:** `domain/service/AuthService.kt`

---

## Phase 2 — Social Login (Google + GitHub) ✅ Complete

> Highest adoption impact. Two providers first; generic OIDC support follows once these are stable.

### Database

- **V17** `identity_providers` — per-tenant provider configuration:
  `(id, tenant_id, provider ENUM[GOOGLE, GITHUB], client_id VARCHAR, client_secret_encrypted VARCHAR, enabled BOOL, created_at)`
- **V18** `social_accounts` — bridge between provider identity and KotAuth user:
  `(id, user_id FK, provider, provider_user_id VARCHAR, provider_email VARCHAR, linked_at)`
  Unique constraint on `(provider, provider_user_id)`.

### Domain

- `IdentityProvider` model, `SocialAccount` model
- `IdentityProviderRepository` port — CRUD + `findByTenantAndProvider(tenantId, provider)`
- `SocialAccountRepository` port — `findByProviderUserId(provider, providerId)`, `findByUserId(userId)`, `create(account)`
- `SocialLoginService` — callback handler:
  1. Receive provider authorization code
  2. Exchange for access token via provider HTTP API
  3. Fetch user profile from provider
  4. Look up existing `social_account` by `(provider, providerUserId)`
  5. If found → retrieve linked KotAuth user and authenticate
  6. If not found → check for existing user by email (auto-link if match found)
  7. If no match → create new user account and link
  8. Proceed to MFA challenge if tenant policy requires it
  9. Issue tokens / authorization code for OAuth flows

### Adapters

- `GoogleOAuthAdapter` — exchanges code via `https://oauth2.googleapis.com/token`, fetches
  profile from `https://www.googleapis.com/oauth2/v3/userinfo`. Implements `SocialProviderPort`.
- `GitHubOAuthAdapter` — exchanges code via `https://github.com/login/oauth/access_token`,
  fetches profile from `https://api.github.com/user` and primary email from
  `https://api.github.com/user/emails`. Implements `SocialProviderPort`.
- Both adapters use Ktor `HttpClient` (already a transitive dependency via Ktor server).

### Auth Routes

- `GET /t/{slug}/social/{provider}` — builds provider authorization URL with `state` (CSRF token
  stored in signed cookie), redirects user to provider consent screen.
- `GET /t/{slug}/social/{provider}/callback` — receives `code` + `state`, verifies CSRF state,
  delegates to `SocialLoginService`, continues the normal post-login flow (MFA check, OAuth code
  issuance or direct token response).

### Admin UI

- New **Identity Providers** tab in workspace settings (same visual pattern as SMTP configuration tab).
- Toggle enable/disable per provider; input client ID and client secret (encrypted via existing
  `EncryptionService.encrypt()`).
- Shows current provider status (enabled, disabled, not configured).

### Account Linking Strategy

Email match = automatic link. If a social login email matches an existing KotAuth account within
the same tenant, accounts are linked silently. No confirmation step. Reasoning: same email address
within the same tenant almost always means the same person. Stricter confirmation can be added as
a tenant policy option later.

---

## Phase 3 — Admin REST API ✅ Complete

> Enables programmatic integration. Required for CI/CD provisioning and external tooling.

### 3a — API Key Infrastructure

**Why first:** All REST routes and webhook delivery depend on this. Nothing in 3b or 4 starts until keys work.

**Database V19** `api_keys`:
```sql
id, tenant_id, name VARCHAR, key_prefix VARCHAR(8), key_hash VARCHAR,
scopes TEXT[], expires_at TIMESTAMPTZ nullable,
last_used_at TIMESTAMPTZ, enabled BOOL, created_at TIMESTAMPTZ
```

**Key format:** `kauth_<tenantSlug>_<32-random-bytes-base64url>` — recognizable, tenant-identifiable from the prefix alone.

The plaintext key is returned **once** at creation and never stored. Only the SHA-256 hash is persisted. The `key_prefix` (first 8 chars after the slug segment) is stored for display without compromising the full key. Same pattern as client secrets.

**Domain:** `ApiKey` model, `ApiKeyRepository` port, `ApiKeyService` (generate, validate, revoke).

Scopes are strings (`users:read`, `users:write`, `roles:read`, etc.). Validated at the route level only — domain layer does not enumerate scopes, keeping it framework-agnostic.

**Ktor auth:** New `apiKey` authentication provider — reads `Authorization: Bearer kauth_...`, SHA-256 hashes it, looks it up in DB, checks enabled + expiry + tenant scope, populates a `TenantPrincipal` for downstream handlers. Slots into Ktor's existing `install(Authentication)` block.

**Admin UI:** New **API Keys** tab in workspace settings. Lists active keys by name + prefix (never full key). Create form: name + optional expiry + scope checkboxes. Revoke button per key.

---

### 3b — REST Routes (`/api/v1/`)

All routes return JSON. All require API key auth. All are scoped to the tenant embedded in the key. All mutations delegate to existing domain services — zero business logic in routes.

**Users**
```
GET    /api/v1/users                         paginated (cursor), ?q= search
POST   /api/v1/users                         create user
GET    /api/v1/users/{id}                    user + role + group membership
PUT    /api/v1/users/{id}                    update profile fields
DELETE /api/v1/users/{id}                    delete user
POST   /api/v1/users/{id}/roles/{roleId}     assign role
DELETE /api/v1/users/{id}/roles/{roleId}     unassign role
```

**Roles**
```
GET    /api/v1/roles                         list roles
POST   /api/v1/roles                         create role
PUT    /api/v1/roles/{id}                    update role
DELETE /api/v1/roles/{id}                    delete role
```

**Groups**
```
GET    /api/v1/groups                        list groups
POST   /api/v1/groups                        create group
PUT    /api/v1/groups/{id}                   update group
DELETE /api/v1/groups/{id}                   delete group
POST   /api/v1/groups/{id}/members/{userId}  add member
DELETE /api/v1/groups/{id}/members/{userId}  remove member
```

**Applications**
```
GET    /api/v1/applications                  list applications
POST   /api/v1/applications                  create application
PUT    /api/v1/applications/{id}             update application
DELETE /api/v1/applications/{id}             delete application
```

**Sessions & Audit**
```
GET    /api/v1/sessions                      active sessions (paginated)
DELETE /api/v1/sessions/{id}                 revoke session
GET    /api/v1/audit-logs                    paginated audit log (read-only)
```

**Response envelope** (consistent across all routes):
```json
{ "data": { ... }, "meta": { "cursor": "...", "hasMore": true } }
```

**Errors** follow RFC 7807 Problem Details:
```json
{
  "type": "https://kotauth.dev/errors/not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "User 123 does not exist in this tenant"
}
```

**Pagination:** Cursor-based on `(created_at, id)` composite. Avoids offset drift on live data — critical for audit logs and sessions.

**Scope gates at route level, not service level.** `AdminService` is reused directly for mutations. Scope validation (`users:write` required for POST/PUT/DELETE on `/users`) is enforced in a route-level middleware interceptor, not duplicated into the domain.

**Intentionally excluded from V1:**
- Bulk user operations (complexity high, demand low — post-V1)
- Per-user session listing via nested route (covered by `GET /api/v1/sessions?userId=`)
- Group attribute management via REST (admin UI handles it; post-V1 for API)

---

### 3c — OpenAPI Spec + Swagger UI

Written **after** 3b routes stabilize — not before. Writing spec first causes drift when implementation details change.

- Spec: `src/main/resources/openapi/v1.yaml`
- Swagger UI served at `/api/docs` via Ktor's `ktor-server-swagger` plugin (no new dependency)
- Covers all 3b endpoints, API key Bearer auth, request/response schemas, pagination, RFC 7807 errors

---

## Phase 4 — Webhooks ✅ Complete

> Async event notifications for integrations. Builds on Phase 3 patterns.

### Database

- **V20** `webhook_endpoints`:
  `(id, tenant_id, url VARCHAR, secret_hash VARCHAR, events TEXT[], enabled BOOL, created_at)`
- **V21** `webhook_deliveries`:
  `(id, endpoint_id, event_type VARCHAR, payload JSONB, status ENUM[pending, delivered, failed], attempts INT, last_attempt_at TIMESTAMP, response_status INT, created_at)`

### Implementation

- `WebhookEndpoint`, `WebhookDelivery` domain models
- `WebhookService` — after every audit event write, fan out to subscribed webhook endpoints
- Delivery: async Kotlin coroutine, 3 retry attempts with exponential backoff (immediate, 5 min, 30 min)
- Payload signature: `X-KotAuth-Signature: HMAC-SHA256(secret, payload)` for receiver verification
- Admin UI: **Webhooks** tab in workspace settings — manage endpoints, select event subscriptions,
  view recent delivery history with response status

### Supported Events at Launch

`user.created`, `user.updated`, `user.deleted`, `login.success`, `login.failed`,
`password.reset`, `mfa.enrolled`, `session.revoked`

---

## Phase 5 — Documentation

> Written after the feature surface is stable.

### Files

| File | Content |
|------|---------|
| `README.md` | Project overview, Docker quickstart, env variable reference, link to docs |
| `CONTRIBUTING.md` | Local dev setup, test instructions, branch conventions, PR process |
| `docs/integration/NEXTJS.md` | Full Next.js App Router + Auth.js OIDC integration example |
| `docs/integration/GENERIC_OIDC.md` | Generic integration pattern (any OIDC-compatible client library) |
| `docs/API.md` | REST API quick reference; points to Swagger UI for full interactive spec |

---

## Execution Order

```
Phase 1  (1.1 + 1.2 + 1.3)  ✅ → Correctness fixes
Phase 2                       ✅ → Social login (Google + GitHub)
Phase 3a                      ✅ → API key infrastructure
Phase 3b                      ✅ → REST routes — full CRUD via API
Phase 3c                      ✅ → OpenAPI spec + Swagger UI
Phase 4                       ✅ → Webhooks — builds on Phase 3 patterns
Phase 5                          → Documentation — written against final feature surface
```

---

## Intentionally Out of V1 Scope

The following are documented and deferred to post-V1:

- LDAP / Active Directory integration
- SAML 2.0 (SP-initiated and IdP-initiated)
- Generic OIDC identity provider (third provider after Google + GitHub stabilize)
- WebAuthn / Passkeys
- SMS OTP
- User impersonation
- Bulk user operations
- Per-client theme override
- Custom CSS injection
- Portal OAuth upgrade (from signed cookie session to first-party access token)
- MFA pending cookie encryption (currently signed HMAC-SHA256; encryption is an upgrade)
