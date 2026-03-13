# KotAuth — Product Roadmap & Architecture Plan

> Last updated: 2026-03-13
> Status: Phase 3 complete — moving to Phase 4 (Identity Federation)

---

## Honest Scope Assessment

Before anything else: you are building in the same space as Keycloak (15 years, hundreds of contributors), Auth0 (acquired by Okta for $6.5B), and Clerk ($1B+ valuation, 50+ engineers). This is not a reason not to build it — open source has beaten commercial IAM before — but it dictates how ruthless you need to be about phasing and what your actual differentiator is.

**Keycloak's real weaknesses (your opportunity):**
- Admin UI is genuinely terrible — dated, complex, confusing for non-experts
- Setup complexity is punishing for small teams
- Documentation is dense and assumes deep OIDC knowledge
- Kubernetes/cloud-native experience is poor out of the box
- Theming/customization requires deep Freemarker knowledge

**Your stated advantages:**
- Great UI/UX out of the box (the largest real gap in the market)
- Docker/cloud-native first architecture
- Modernized developer experience

**The constraint you must accept:** You cannot compete on features with Keycloak at parity. You compete by being 10x simpler for the 80% use case while not sacrificing security.

---

## Is the Tenant/Client Model an Industry Standard?

Yes — unconditionally. The concept is universal across every serious IAM platform, only the naming differs:

| Platform    | Namespace unit | Application unit |
|-------------|---------------|------------------|
| Keycloak    | Realm          | Client           |
| Auth0       | Tenant         | Application      |
| Okta        | Organization   | Application      |
| Azure AD    | Tenant         | App Registration |
| Clerk       | Instance       | Application      |
| **KotAuth** | **Tenant**     | **Client**       |

This maps directly to OAuth 2.0 / OIDC:
- A **Tenant** IS an Authorization Server — it has its own issuer URL, user directory, keys, token settings, and identity providers
- A **Client** IS an OAuth 2.0 Client — it has a client_id, client_secret, redirect URIs, allowed scopes, and an access type (public vs confidential)

**What this means for the current codebase:** the flat `users` table must become tenant-scoped. This is the single most important data model decision to make before building further. Every table you add from here will have `tenant_id` as a foreign key.

---

## Core Data Model (Design this now, before Phase 1 coding)

```
Tenants
  id, slug, display_name, issuer_url
  token_expiry_seconds, refresh_token_expiry_seconds
  registration_enabled, email_verification_required
  password_policy_min_length, password_policy_require_special
  created_at

Clients
  id, tenant_id (FK), client_id (unique per tenant), client_secret_hash
  name, description
  access_type: ENUM(public, confidential, bearer_only)
  allowed_redirect_uris (array or separate table)
  allowed_scopes (array or separate table)
  token_expiry_override (nullable — inherits from tenant if null)
  enabled, created_at

Users
  id, tenant_id (FK), username, email, full_name
  password_hash, email_verified, enabled
  created_at, last_login_at

Roles
  id, tenant_id (FK), name, description
  is_composite (role that contains other roles)

UserRoles
  user_id (FK), role_id (FK)

ClientScopes
  id, tenant_id (FK), name, description, protocol (openid, saml)
  include_in_token_scope

Sessions  (Phase 2)
  id (UUID), user_id (FK), tenant_id (FK), client_id (FK)
  access_token_hash, refresh_token_hash
  ip_address, user_agent
  created_at, expires_at, last_activity_at, revoked_at

TenantIdentityProviders  (Phase 3)
  id, tenant_id (FK), provider_type (google, github, saml, oidc)
  client_id, client_secret_encrypted
  enabled, config (JSONB)

AuditLog  (Phase 2)
  id, tenant_id (FK), user_id (nullable FK), event_type
  ip_address, details (JSONB), created_at
```

**The master tenant:** One special tenant (`master`) contains admin users who can manage all other tenants. This separates platform operators from tenant users cleanly and enables a proper multi-tenant SaaS model.

---

## Phase Roadmap

### Phase 0 — Foundation (Current Sprint)
*Goal: Honest production-readiness for the single-tenant PoC*

The current code is not deployable as-is. These are blockers:

- [ ] **Email verification flow** — without this, anyone can register with fake emails
- [ ] **Password reset / forgot password flow** — no auth system ships without this
- [ ] **Rate limiting on auth endpoints** — /login and /register are wide open to brute force
- [ ] **CSRF protection** on form endpoints
- [ ] **Secure defaults** — JWT_SECRET must be enforced as non-default in production; add a startup check that fails if `secret-key-12345` is used with `KAUTH_ENV=production`
- [ ] **Refresh token persistence** — currently UUIDs are generated but never stored; they can't be invalidated
- [ ] **HTTPS enforcement** — add redirect or at minimum a startup warning
- [ ] **Input sanitization** — trim and validate all form inputs server-side

---

### Phase 1 — Multi-Tenancy Core
*Goal: The tenant/client model that makes this a platform, not a single-app auth service*

**Data model migration:**
- Add `tenants` table, migrate existing users to a default tenant
- Add `clients` table (current JWT config becomes a client record)
- Scope all user operations by `tenant_id`
- Introduce Flyway for versioned schema migrations (replace `SchemaUtils` which is dev-only tooling)

**Tenant management:**
- Master tenant created on first boot
- Admin can create/configure additional tenants
- Each tenant has its own: issuer URL, token settings, registration toggle, password policy
- Tenant slug becomes part of the endpoint URL: `/t/{slug}/protocol/openid-connect/token`

**Client management:**
- Register clients per tenant (client_id + secret for confidential, just client_id for public)
- Allowed redirect URIs validated on authorization requests
- Token expiry configurable per client (overrides tenant default)

**Admin UI — Phase 1 (this is your differentiator, invest here):**
- Tenant list + create tenant
- Client list + create/edit client
- Basic user list per tenant
- Clean, opinionated design — NOT a Keycloak replica

---

### Phase 2 — OAuth 2.0 / OIDC Compliance
*Goal: Standard protocol flows so any OAuth2-compatible client library works with KotAuth*

This is not optional. Without standard flows, you are not an IAM platform — you are a login page library. Every framework (Next.js, Spring, Rails, Django) has OIDC client libraries that expect these endpoints.

**Endpoints to implement (in priority order):**

```
GET  /t/{tenant}/.well-known/openid-configuration   # Discovery (partial: done)
GET  /t/{tenant}/protocol/openid-connect/certs      # JWKS — public keys for token verification
POST /t/{tenant}/protocol/openid-connect/token      # Token endpoint
GET  /t/{tenant}/protocol/openid-connect/auth       # Authorization endpoint (code flow)
POST /t/{tenant}/protocol/openid-connect/logout     # End session
GET  /t/{tenant}/protocol/openid-connect/userinfo   # Userinfo (who is this token for?)
POST /t/{tenant}/protocol/openid-connect/revoke     # Token revocation
POST /t/{tenant}/protocol/openid-connect/introspect # Token introspection (is this valid?)
```

**Flows to implement (in priority order):**

1. **Authorization Code Flow + PKCE** — for web apps and SPAs. This is the primary flow for 90% of integrations.
2. **Client Credentials Flow** — for machine-to-machine (backend services, CI/CD, etc.)
3. **Refresh Token Flow** — rotate refresh tokens on every use (prevent replay attacks)
4. **Resource Owner Password Credentials (ROPC)** — deprecated but still requested by legacy integrations. Optional.

**Token improvements:**
- RS256 signing (asymmetric) — allows clients to verify tokens without contacting KotAuth
- JWKS endpoint to publish public keys
- Standard claims: `sub`, `iss`, `aud`, `exp`, `iat`, `jti`, `email`, `email_verified`, `name`, `preferred_username`, `roles`
- Refresh token persistence with rotation and absolute expiry

**Session management:**
- Track active sessions per user/client
- Allow users to see and revoke their own sessions
- Allow admins to revoke any session
- Concurrent session limits (configurable per tenant)

---

### Phase 3 — User Management Platform ✅ Complete
*Goal: The admin console and user self-service that makes KotAuth a complete platform*

**Admin console (Phase 3a ✅):**
- ✅ User search, filter, pagination
- ✅ User detail: profile, sessions, audit log
- ✅ Create/edit/disable users
- ✅ Force password reset (admin-initiated)
- ❌ Impersonate user — deferred (future, requires strict audit logging)
- ❌ Bulk operations — deferred (future)

**User self-service portal (Phase 3b ✅):**
- ✅ View/edit own profile
- ✅ Change password (requires current password)
- ✅ Active sessions — see and revoke
- ✅ MFA enrollment and management (Phase 3c)
- ❌ Account deletion request — deferred (future)

**Roles & Permissions (Phase 3c ✅):**
- ✅ Tenant roles (global to the tenant)
- ✅ Client roles (scoped to a specific client)
- ✅ Composite roles with BFS cycle detection
- ✅ Role assignment via admin API routes
- ✅ JWT claims: `realm_access.roles` + `resource_access.{clientId}.roles` (Keycloak-compatible)
- ✅ Effective role resolution: direct + group-inherited + composite expansion

**Groups (Phase 3c ✅):**
- ✅ User groups with role inheritance
- ✅ Group hierarchy (nested groups via `parent_group_id`)
- ✅ Group-level attributes (JSONB)

**Password policies (Phase 3c ✅, configurable per tenant):**
- ✅ Minimum length
- ✅ Require uppercase / special characters / numbers
- ✅ Password history (can't reuse last N passwords, BCrypt-verified)
- ⚠️ Expiry (column exists, enforcement deferred)
- ✅ Blacklist (50 common passwords seeded, tenant-specific additions supported)

**MFA (Phase 3c ✅):**
- ✅ TOTP (Google Authenticator, Authy) — RFC 6238, zero-dependency implementation
- ✅ Recovery codes (8 codes, BCrypt-hashed, consumed on use)
- ❌ WebAuthn / Passkeys — deferred to Phase 4 or 5 (`MfaMethod` enum is extensible)
- ❌ SMS OTP — deferred (future, controversial due to SIM swap risk)
- ✅ MFA policy per tenant: optional, required for all, required for admins (partial — admin check deferred)

**Email flows (Phase 3b ✅):**
- ✅ Email verification on registration
- ✅ Password reset
- ❌ MFA backup code delivery — deferred (codes shown at enrollment time only)
- ❌ Login notification (new device/location) — deferred (future)
- ❌ Account lockout notification — deferred (future)
- ✅ Configurable SMTP per tenant (AES-256-GCM encrypted passwords)

---

### Phase 4 — Identity Federation
*Goal: Connect KotAuth to external identity sources*

**Social login (OAuth 2.0 providers):**
- Google
- GitHub
- Microsoft
- Generic OIDC provider (covers any standards-compliant IdP)
- Account linking (connect existing account to social login)

**Enterprise integrations:**
- LDAP / Active Directory sync (read users from corporate directory)
- SAML 2.0 SP-initiated and IdP-initiated flows (enterprise requirement for most Fortune 500)
- External OIDC provider (KotAuth as a relay/broker)

---

### Phase 5 — Developer Experience
*Goal: Make integration trivially easy — this is where Clerk wins and Keycloak loses*

**Admin REST API:**
- Full CRUD for all entities via REST
- API keys scoped per tenant
- Webhook events (user.created, user.updated, login.success, login.failed, etc.)
- OpenAPI spec with interactive docs

**SDKs / Integration guides:**
- Next.js integration guide (with code)
- Spring Boot integration guide
- A JavaScript/TypeScript SDK wrapper
- Docker Compose example for local dev

**Observability:**
- Structured JSON logs (already have logback — needs structured format)
- Metrics endpoint (Prometheus format) — login rates, error rates, token issuance
- Health check endpoint: `/health` (DB connectivity, config validity)
- Audit log API (queryable by admin)

---

## What to Build Next (Immediate Priority Order)

Given where the PoC is today, this is the honest sequencing:

1. **Design and migrate to the tenant/client data model** — everything else is blocked on this. Do not add features to the flat schema.
2. **Phase 0 security blockers** — email verification, password reset, rate limiting. Without these, the software is not deployable for any real use case.
3. **Authorization Code Flow + PKCE** — this single flow makes KotAuth usable with any OIDC client library in any language. It's the unlocking feature.
4. **Admin console v1** — tenant/client/user management. This is your UI/UX differentiator. Invest in it.
5. **TOTP MFA** — table stakes for any auth platform claiming to be production-grade.

---

## UI/UX Principles (Non-Negotiable)

Keycloak's admin console is the cautionary tale. These principles guard against repeating it:

- **Progressive disclosure** — the happy path (create tenant → create client → get token) should take 5 minutes without reading docs. Advanced settings are always one level deeper.
- **Inline guidance** — every field that isn't obvious gets a tooltip or inline explanation. No assumption of OIDC expertise from the person setting up their first tenant.
- **Consistent design system** — the public-facing auth pages (login, register, MFA, password reset) and the admin console share the same design tokens. Theming one themes both.
- **Per-tenant theming** — logo, colors, and copy are configurable per tenant. The login page for Tenant A looks like Company A's brand. This is a hard requirement for multi-tenant deployments.
- **Mobile-first auth flows** — login/register/MFA must work on mobile browsers without compromise. The admin console can be desktop-first.
- **Meaningful error messages** — "An error occurred" is not an error message. Every failure state has a specific, actionable message.

---

## Technical Architecture Decisions

### ADR-001: Tenant URL structure
**Decision:** `/t/{tenant-slug}/...` prefix for all tenant-scoped endpoints
**Rationale:** Short, clean URL that avoids collision with admin routes; slug is human-readable in logs; `/t/` is more concise than `/tenants/` for URLs that appear in every token request

### ADR-002: Token signing algorithm
**Decision:** RS256 (RSA + SHA-256) as default, configurable to ES256 (ECDSA)
**Rationale:** RS256 allows token verification without a network call to KotAuth (clients use public key from JWKS); HS256 (current) requires sharing the secret with every verifying service — does not scale

### ADR-003: Refresh token storage
**Decision:** Store refresh token hash in DB (sessions table), rotate on every use
**Rationale:** Stateless refresh tokens cannot be revoked; refresh token rotation with server-side storage is the OIDC best practice (RFC 6749 + Security BCP RFC 9700)

### ADR-004: Admin vs end-user separation
**Decision:** Admin console at `/admin/...` behind `master` tenant authentication; end-user flows at `/t/{slug}/...`
**Rationale:** Cleanly separates platform operators from tenant users; enables future multi-instance deployments; admin compromise does not expose individual tenant auth flows

### ADR-005: Database migrations
**Decision:** Introduce Flyway for schema migrations before Phase 1
**Rationale:** `SchemaUtils.createMissingTablesAndColumns` is development-only tooling; adding `tenant_id` foreign keys and all Phase 1 tables requires proper versioned, reversible migrations

---

## What KotAuth Is NOT

Being explicit about scope boundaries prevents feature creep and keeps the product focused:

- **Not a secrets manager** (use Vault, AWS Secrets Manager)
- **Not a full SIEM** (audit logs yes; threat detection no)
- **Not a CDN or WAF** (put Cloudflare in front)
- **Not a directory replacement** (LDAP sync yes; native LDAP protocol no)
- **Not multi-region HA out of the box** (stateless tokens help; session replication is Phase 6+)
