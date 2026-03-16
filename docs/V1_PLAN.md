# KotAuth V1 Implementation Plan

**Status:** In Progress
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

## Phase 2 — Social Login (Google + GitHub)

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

## Phase 3 — Admin REST API

> Enables programmatic integration. Required for CI/CD provisioning and external tooling.

### 3a — API Key Infrastructure

**Database V19** `api_keys`:
`(id, tenant_id, name VARCHAR, key_prefix VARCHAR(8), key_hash VARCHAR, scopes TEXT[], expires_at TIMESTAMP nullable, last_used_at TIMESTAMP, enabled BOOL, created_at TIMESTAMP)`

**Key format:** `kauth_<tenantSlug>_<random-32-bytes-base64url>`
The plaintext key is returned **once** at creation and never stored. Only the SHA-256 hash is
persisted. The `key_prefix` (first 8 chars) is stored for display purposes without compromising
the full key.

**Domain:** `ApiKey` model, `ApiKeyRepository` port, `ApiKeyService` (generate, revoke, validate).

**Ktor Auth:** A new `apiKeyAuth` auth scheme that looks up and validates the key hash, resolves
the tenant scope, and populates the Ktor `Principal` for downstream route handlers.

**Admin UI:** New **API Keys** tab in workspace settings. Lists active keys (prefix + name, no
full key), create with name + optional expiry + scope selection, revoke.

---

### 3b — REST Routes (`/api/v1/`)

All routes return JSON. All require API key auth. All are scoped to the tenant embedded in the key.

**Users**
- `GET    /api/v1/users` — paginated list (cursor-based), filterable by email/username
- `POST   /api/v1/users` — create user
- `GET    /api/v1/users/{id}` — get user with role/group membership
- `PUT    /api/v1/users/{id}` — update user fields
- `DELETE /api/v1/users/{id}` — delete user
- `POST   /api/v1/users/{id}/roles` — assign role
- `DELETE /api/v1/users/{id}/roles/{roleId}` — unassign role

**Roles**
- `GET    /api/v1/roles` — list roles
- `POST   /api/v1/roles` — create role
- `GET    /api/v1/roles/{id}` — get role with members
- `PUT    /api/v1/roles/{id}` — update role
- `DELETE /api/v1/roles/{id}` — delete role

**Groups**
- `GET    /api/v1/groups` — list groups
- `POST   /api/v1/groups` — create group
- `GET    /api/v1/groups/{id}` — get group with members and roles
- `PUT    /api/v1/groups/{id}` — update group
- `DELETE /api/v1/groups/{id}` — delete group

**Applications**
- `GET    /api/v1/applications` — list applications
- `POST   /api/v1/applications` — create application
- `GET    /api/v1/applications/{id}` — get application
- `PUT    /api/v1/applications/{id}` — update application
- `DELETE /api/v1/applications/{id}` — delete application

**Sessions**
- `GET    /api/v1/sessions` — list active sessions (paginated)
- `DELETE /api/v1/sessions/{id}` — revoke session

**Audit Logs**
- `GET    /api/v1/audit-logs` — paginated audit log (read-only)

**Response format:** Consistent JSON envelope:
```json
{
  "data": { ... },
  "meta": { "cursor": "...", "hasMore": true, "total": 42 }
}
```
Errors follow RFC 7807 Problem Details:
```json
{
  "type": "https://kotauth.dev/errors/not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "User 123 does not exist in this tenant"
}
```

**Pagination:** Cursor-based using `(created_at, id)` composite to avoid offset drift.

**Important:** All routes delegate to existing domain services. No business logic duplication.

---

### 3c — OpenAPI Spec + Swagger UI

- Spec written in YAML at `src/main/resources/openapi/v1.yaml`
- Swagger UI served at `/api/docs` via Ktor's `ktor-server-swagger` plugin
- Covers all Phase 3b endpoints, authentication (API key Bearer), request/response schemas,
  pagination, error codes

Written **after** Phase 3b routes are stable to avoid spec drift.

---

## Phase 4 — Webhooks

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
Phase 1  (1.1 + 1.2 + 1.3)  → Correctness fixes — no new features, unblocks everything
Phase 2                       → Social login — highest adoption impact, self-contained
Phase 3a                      → API key infrastructure — prerequisite for REST + webhooks
Phase 3b                      → REST routes — full CRUD via API
Phase 3c                      → OpenAPI spec — written after routes are stable
Phase 4                       → Webhooks — builds on Phase 3 patterns
Phase 5                       → Documentation — written against final feature surface
```

Phases 3 and 2 are independent and could be parallelized with multiple contributors.

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
