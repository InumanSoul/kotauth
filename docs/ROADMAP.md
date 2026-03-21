# Kotauth — Product Roadmap

> Last updated: 2026-03-21
> Status: V1.0.3 — Phases 0–5 shipped

---

## Strategic Context

Kotauth competes in a space occupied by Keycloak (15 years, hundreds of contributors), Auth0 (acquired by Okta for $6.5B), and Clerk ($1B+ valuation, 50+ engineers). This is not a reason to avoid the space — open source has displaced commercial IAM before — but it dictates how ruthlessly the project must phase its scope and where it must differentiate.

**Keycloak's real weaknesses (Kotauth's opportunity):**
- Admin UI is genuinely poor — dated, complex, confusing for operators who aren't OIDC experts
- Setup complexity is punishing for small teams
- Documentation assumes deep protocol knowledge upfront
- Kubernetes/cloud-native experience requires significant configuration work
- Theming requires Freemarker expertise and internal Keycloak knowledge

**Kotauth's differentiators:**
- Polished UI out of the box — the largest real gap in the open-source IAM market
- Docker/cloud-native first architecture — running in under five minutes
- Developer-first experience — discoverable, well-documented, standard protocol compliance
- Lightweight footprint — single JAR + PostgreSQL, no JBoss/WildFly dependency

**The constraint the project must hold:** Kotauth cannot compete on feature parity with Keycloak. It competes by being dramatically simpler for the 80% use case while not compromising on security fundamentals.

---

## Tenant / Application Model

The tenant and application model is an industry standard, not an opinion. Every serious IAM platform uses the same two-level structure, differing only in naming:

| Platform | Namespace unit | Application unit |
|---|---|---|
| Keycloak | Realm | Client |
| Auth0 | Tenant | Application |
| Okta | Organization | Application |
| Azure AD | Tenant | App Registration |
| Clerk | Instance | Application |
| **Kotauth** | **Workspace** | **Application** |

In protocol terms: a Kotauth Workspace is an OAuth 2.0 Authorization Server. It owns its user directory, signing keys, token policies, and identity providers. Each Application within a workspace is an OAuth 2.0 Client with its own `client_id`, allowed redirect URIs, scopes, and token lifetime overrides.

---

## Release History

### Phase 0 — Foundation ✅ Shipped
*Goal: Honest production-readiness baseline*

The initial implementation established the security and operational primitives that all subsequent phases build on:

- Email verification flow — prevents registration with unverifiable addresses
- Password reset via signed token with email delivery
- Rate limiting on `/login` (5/min) and `/register` (3/5 min) per IP
- CSRF protection on all form endpoints
- Startup validation — server refuses to start if `KAUTH_ENV=production` and unsafe defaults are detected
- Refresh token persistence with SHA-256 hashing — tokens are invalidatable, never stored in plaintext
- HTTPS enforcement at startup — OIDC discovery, OAuth callbacks, and session cookies require TLS in production
- Server-side input validation on all form and API inputs

---

### Phase 1 — Multi-Tenancy Core ✅ Shipped
*Goal: The workspace/application model that makes Kotauth a platform, not a per-app login service*

Phase 1 introduced full multi-tenancy. Every table carries `tenant_id`. The same Kotauth instance serves multiple isolated identity directories simultaneously.

- Flyway versioned schema migrations replacing `SchemaUtils` development tooling
- `tenants` table — slug-routed, each workspace owns its issuer URL, token TTLs, and password policy
- `applications` table — per-tenant OAuth2 clients with `client_id`, bcrypt-hashed secrets, redirect URIs, scope lists, and token TTL overrides
- Tenant-scoped user directory — the same email address is independent across workspaces
- Master tenant — the `master` workspace holds platform admin accounts, cleanly separated from end-user tenants
- Per-tenant RS256 key pairs — provisioned on first use, stored as PEM, no shared signing key
- Admin console — workspace list, application management, basic user management
- Tenant slug in all endpoint URLs: `/t/{slug}/protocol/openid-connect/...`

---

### Phase 2 — OAuth 2.0 / OIDC Compliance ✅ Shipped
*Goal: Standards-compliant protocol flows so any OIDC client library works with Kotauth without modification*

Full RFC 6749 / RFC 7636 / OpenID Connect Core compliance. Every OAuth2-compatible framework and client library integrates without custom adapters.

**Endpoints:**
- `GET /.well-known/openid-configuration` — OIDC discovery document
- `GET /protocol/openid-connect/certs` — JWKS for offline token verification
- `GET /protocol/openid-connect/auth` — authorization endpoint
- `POST /protocol/openid-connect/token` — all grant types
- `GET|POST /protocol/openid-connect/logout` — end session
- `GET /protocol/openid-connect/userinfo` — standard claims over bearer auth
- `POST /protocol/openid-connect/revoke` — RFC 7009 token revocation
- `POST /protocol/openid-connect/introspect` — RFC 7662 token introspection

**Grant types:**
- Authorization Code + PKCE (primary flow for web apps and SPAs — PKCE required for all public clients)
- Client Credentials (machine-to-machine)
- Refresh Token with rotation — each use issues a new refresh token; the old one is immediately invalidated

**Token claims:** `sub`, `iss`, `aud`, `exp`, `iat`, `jti`, `email`, `email_verified`, `name`, `preferred_username`, `realm_access.roles`, `resource_access.{clientId}.roles`

**Session management:** per-user session tracking, user-initiated revocation, admin-initiated revocation, absolute refresh token expiry

---

### Phase 3 — User Management Platform ✅ Shipped
*Goal: Complete admin console and user self-service — the UI/UX layer that distinguishes Kotauth from Keycloak*

Phase 3 was the largest phase, delivered in four increments (3a–3d).

**Admin console (3a):**
- User search, filter, pagination
- User detail view — profile, active sessions, audit trail
- Create, edit, and disable users
- Application management — client secret rotation, redirect URI management
- API key management — generate, scope, revoke keys with SHA-256 hashing
- Audit log viewer — paginated, filterable by event type

**Email flows and self-service (3b):**
- Email verification on registration with per-tenant SMTP configuration
- Password reset with expiring signed tokens, no email enumeration
- User self-service portal — profile editing, password change, session listing and revocation
- Per-tenant SMTP configuration with AES-256-GCM encryption of stored credentials

**RBAC and Security (3c):**
- Tenant roles and application-scoped roles
- Composite roles with BFS expansion and cycle detection at assignment time
- Groups with nested hierarchy and role inheritance — users inherit roles through group membership
- Password policies — minimum length, character class requirements, history depth, common password blacklist
- TOTP MFA (RFC 6238) — enrollment flow with QR codes, verification, recovery codes
- MFA policy per workspace: `optional`, `required`, `required_for_admins`
- REST API v1 — 30+ endpoints covering all entities, OpenAPI 3.1 spec, Swagger UI

**Branding and portal (3d):**
- Per-tenant theming — logo, primary colors, workspace name on all auth pages
- Self-service portal via OAuth Authorization Code + PKCE using the built-in `kotauth-portal` client provisioned per tenant on startup
- Dedicated Security Settings page — password policy and MFA policy configuration separated from general workspace settings

---

### Phase 4 — Webhooks ✅ Shipped
*Goal: Real-time event delivery so downstream systems stay in sync without polling*

- Webhook endpoint management — register URLs, select event subscriptions
- HMAC-SHA256 request signing — `X-KotAuth-Signature: sha256=...` on every delivery
- Asynchronous delivery — webhook fan-out does not block the auth flow
- Exponential backoff retry — three attempts: immediate, 5 minutes, 30 minutes
- Delivery history — per-endpoint attempt log with status, response code, and error detail
- Eight event types: `user.created`, `user.updated`, `user.deleted`, `login.success`, `login.failed`, `password.reset`, `mfa.enrolled`, `session.revoked`

---

### Phase 5 — Documentation and Release ✅ Shipped
*Goal: The external-facing surface that makes adoption possible*

- README with Docker quickstart — running in under five minutes from a fresh clone
- Environment variable reference — every variable, type, default, and production guidance
- React SPA + TanStack Router integration guide — end-to-end OIDC with `oidc-client-ts`, auth guards, silent token refresh
- CONTRIBUTING guide — local setup, architecture constraints, migration conventions, PR process
- Security fix: `cookie.secure` now derived from `KAUTH_BASE_URL` at startup — cookies carry the `Secure` flag automatically in HTTPS deployments

---

## Post-V1 Roadmap

The following phases are planned but not yet scheduled. Priority order reflects market demand and dependency on existing foundations.

---

### Phase 6 — Enterprise Federation
*Goal: Connect Kotauth to corporate identity sources*

The enterprise market requires LDAP and SAML. Without these, Kotauth cannot be adopted in organizations with existing Active Directory infrastructure or legacy SSO contracts.

- **LDAP / Active Directory sync** — read users and groups from a corporate directory, configurable sync interval, attribute mapping
- **SAML 2.0** — SP-initiated and IdP-initiated flows, assertion parsing, attribute mapping to Kotauth user model
- **External OIDC broker** — Kotauth acting as a relay to an upstream OIDC provider (Azure AD, Okta, etc.)
- **Cross-tenant federation** — allow users from one workspace to authenticate in another via configured trust

---

### Phase 7 — Advanced Authentication Methods
*Goal: Modern, phishing-resistant authentication*

- **WebAuthn / Passkeys** — the `MfaMethod` enum in the domain model is already extensible for this; implementation requires CBOR parsing and authenticator data verification
- **Magic links** — passwordless email login, short-lived signed tokens
- **SMS OTP** — noted as controversial due to SIM swap risk; implementation requires a pluggable SMS provider interface to avoid vendor lock-in

---

### Phase 8 — Observability and Operations
*Goal: Production-grade insight into a running Kotauth deployment*

- **Prometheus metrics** — login rates, error rates by type, token issuance volume, active session count, webhook delivery success rate
- **Structured JSON log output** — the logstash encoder is already in the dependency tree; format finalization and field normalization
- **Webhook retry background sweep** — the `findPending()` port exists; a scheduled background job to recover deliveries missed during downtime
- **Key rotation** — admin-initiated rotation of per-tenant RS256 key pairs with a configurable overlap window for in-flight tokens
- **Helm chart** — Kubernetes deployment manifest with configurable replicas, readiness/liveness probes, and secret management integration
- **Zero-downtime rolling updates** — session and key state compatibility guarantees between adjacent versions

---

### Phase 9 — Platform Expansion
*Goal: Kotauth as a programmable identity layer, not just an auth server*

- **Admin REST API expansion** — bulk user operations, user impersonation with strict audit trail, cross-tenant admin endpoints
- **SDK layer** — typed TypeScript/JavaScript client library wrapping the REST API and OIDC flows
- **Email template customization** — per-tenant HTML email templates with variable substitution
- **Audit log export** — scheduled export to S3-compatible object storage or a SIEM webhook
- **SCIM 2.0** — automated user provisioning and deprovisioning from external HR systems

---

## What Kotauth Is Not

Explicit scope boundaries prevent feature creep and keep the platform coherent:

- **Not a secrets manager** — use Vault, AWS Secrets Manager, or Doppler for application secrets
- **Not a SIEM** — audit logs are queryable and exportable; threat detection and correlation are out of scope
- **Not a CDN or WAF** — Kotauth expects a reverse proxy (nginx, Caddy, Traefik) in front of it
- **Not a directory server** — LDAP sync (read) is planned; serving the LDAP protocol is not
- **Not multi-region HA out of the box** — stateless JWT verification helps; active-active session replication is a Phase 8+ concern

---

## UI/UX Principles

Keycloak's admin console is the cautionary tale. These principles are non-negotiable:

**Progressive disclosure** — the happy path (create workspace → create application → get a working OIDC integration) takes five minutes without reading documentation. Advanced settings are always one level deeper.

**Inline guidance** — every non-obvious field gets an inline explanation or tooltip. No assumption of OIDC expertise from the operator setting up their first workspace.

**Consistent design system** — auth pages (login, register, MFA, password reset) and the admin console share the same design tokens. Theming one themes both.

**Per-tenant branding** — logo, colors, and workspace name are configurable per tenant. The login page for a given workspace reflects that workspace's brand.

**Mobile-first auth flows** — login, register, and MFA must work on mobile browsers without compromise. The admin console is desktop-first.

**Meaningful errors** — "An error occurred" is not an error message. Every failure state has a specific, actionable message that tells the user what happened and what to do about it.

---

## Architecture Decisions

Key architectural choices made during development, documented for future contributors.

| ADR | Decision | Rationale |
|---|---|---|
| ADR-01 | Hexagonal (Ports & Adapters) — zero framework imports in domain | Domain logic is independently testable; adapters are swappable |
| ADR-02 | Flyway for schema migrations | Versioned, irreversible migrations safe for CI/CD and production upgrades |
| ADR-03 | Separate write-only (`AuditLogPort`) vs. read-only (`AuditLogRepository`) audit log paths | Auth path never blocks on query execution; read and write are independently scalable |
| ADR-04 | All mutations routed through domain services, not directly through repositories | Validation and audit logging are centralized; no mutation path bypasses business rules |
| ADR-05 | Client secret stored as bcrypt hash only — raw value shown once at creation | A database breach does not leak application secrets; lost secrets require regeneration |
| ADR-06 | Domain services return `AdminResult<T>` sealed types, not exceptions | Error handling is explicit and exhaustive; no surprise runtime exceptions in route handlers |
| ADR-07 | Portal login uses OAuth Authorization Code + PKCE via built-in `kotauth-portal` client | The portal and third-party apps authenticate identically; no separate session mechanism |
| ADR-08 | Master tenant for platform admins | Platform operator role is cleanly separated from end-user tenants |
| ADR-09 | Per-tenant RS256 key pairs, auto-provisioned | No single point of key failure; tokens are verifiable offline using JWKS; tenant isolation at the cryptographic level |
| ADR-10 | Refresh token rotation on every use | A stolen refresh token has a limited replay window; reuse of a superseded token revokes the entire session chain |
| ADR-11 | Audit events recorded eagerly in the request path | No auth flow succeeds without a corresponding audit record; background delivery risks silent loss |
| ADR-12 | PKCE required for all public clients | Defense against authorization code interception and client ID enumeration |
| ADR-13 | User lookup by `(tenant_id, username)` — no global username namespace | The same email exists independently in different workspaces; no cross-tenant information leakage |
| ADR-14 | MFA pending state via HMAC-signed cookie, not server-side session | Stateless; no session table bloat; HMAC signature prevents userId forgery |
| ADR-15 | OAuth `state` parameter carries HMAC-signed CSRF nonce + OAuth params as Base64 | No extra cookie or session required; the state parameter is self-contained and tamper-evident |
| ADR-16 | Social login auto-links by email address | Reduces friction for users who have both a local and social account with the same email |
| ADR-17 | Social OAuth adapters use `java.net.http` only — no new Gradle dependencies | Lean fat JAR; minimal attack surface; no transitive dependency risk |
| ADR-18 | Social-registered users get a random unusable password hash | The account exists; password authentication is disabled until the user explicitly sets one |
| ADR-19 | API keys use SHA-256, not bcrypt | 256-bit key entropy makes brute force infeasible; SHA-256 eliminates bcrypt latency on every API call |
| ADR-20 | API key prefix stored for display — first 16 characters of the raw key | Human-friendly identification in the admin UI without exposing the full credential |
| ADR-21 | Swagger UI loaded from CDN, not bundled | Saves ~7 MB from the fat JAR; no new Gradle dependencies required |
