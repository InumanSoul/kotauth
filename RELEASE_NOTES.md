# Kotauth v1.0.0 Release Notes

> Released: 2026-03-17

This is the first stable release of Kotauth — a self-hosted, container-native identity and authentication platform. It covers everything needed to replace Keycloak or Auth0 for the 80% use case: OAuth2/OIDC, multi-tenancy, RBAC, MFA, webhooks, and a polished admin UI, all running from a single JAR and a PostgreSQL database.

---

## Getting Started

```bash
git clone https://github.com/InumanSoul/kotauth.git
cd kotauth
cp .env.example .env
docker compose up
```

Admin console: `http://localhost:8080/admin`

OIDC discovery (once you create a workspace): `http://localhost:8080/t/{slug}/.well-known/openid-configuration`

Full setup instructions in [README.md](README.md).

---

## What's Included

### OAuth2 / OIDC

Full RFC 6749, RFC 7636, and OpenID Connect Core compliance. Any OIDC client library integrates without modification.

- Authorization Code + PKCE — PKCE required for all public clients
- Client Credentials — machine-to-machine token issuance
- Refresh Token rotation — each use invalidates the previous token; replayed tokens revoke the full session chain
- `GET /.well-known/openid-configuration` — OIDC discovery
- `GET /protocol/openid-connect/certs` — JWKS for offline token verification
- `POST /protocol/openid-connect/token` — all grant types
- `POST /protocol/openid-connect/introspect` — RFC 7662
- `POST /protocol/openid-connect/revoke` — RFC 7009
- `GET /protocol/openid-connect/userinfo` — standard claims
- JWT claims: `sub`, `iss`, `aud`, `exp`, `iat`, `jti`, `email`, `email_verified`, `name`, `preferred_username`, `realm_access.roles`, `resource_access.{clientId}.roles`

### Multi-Tenancy

Every table is tenant-scoped. One Kotauth instance serves multiple isolated identity directories simultaneously.

- Workspace model — each workspace owns its user directory, signing keys, OAuth clients, token policies, and branding
- Per-workspace RS256 key pairs — provisioned on first use, no shared signing key
- Master workspace for platform admin accounts, cleanly separated from end-user tenants
- Tenant slug in all endpoint URLs: `/t/{slug}/...`

### RBAC

- Tenant roles and application-scoped roles, both included in JWT claims
- Composite roles with BFS expansion and cycle detection at assignment
- Groups with nested hierarchy — users inherit roles through group membership

### MFA

- TOTP (RFC 6238) with QR code enrollment and recovery codes
- Per-workspace MFA policy: `optional`, `required`, or `required_for_admins`
- MFA pending state via HMAC-signed cookie — stateless, forgery-resistant

### Social Login

- Google and GitHub OAuth2
- Automatic account linking by email address
- Social-registered users have password authentication disabled until they explicitly set one

### User Self-Service

- Email verification on registration
- Password reset via signed expiring tokens — no email enumeration
- Self-service portal — profile editing, password change, active session listing and revocation, MFA enrollment

### Admin Console

- Workspace management — settings, token lifetimes, password policy, MFA policy, branding
- User management — search, filter, create, edit, disable, view sessions and audit trail
- Application management — client secret rotation, redirect URI management, token TTL overrides
- API key management — generate, scope, and revoke keys
- Audit log viewer — paginated, filterable by event type
- Webhook management — register endpoints, select event subscriptions, view delivery history

### Webhooks

Eight event types delivered with HMAC-SHA256 signatures: `user.created`, `user.updated`, `user.deleted`, `login.success`, `login.failed`, `password.reset`, `mfa.enrolled`, `session.revoked`. Asynchronous delivery with exponential backoff retry (3 attempts: immediate, 5 min, 30 min).

### REST API

30+ endpoints covering all entities. API key authentication. OpenAPI 3.1 spec with Swagger UI at `/t/{slug}/api/v1/docs`.

### Security Baseline

- bcrypt password hashing
- AES-256-GCM encryption for SMTP credentials at rest
- SHA-256 refresh token and API key hashing — never stored in plaintext
- Rate limiting on login (5/min), register (3/5 min), and token endpoint (20/min) per IP
- Security response headers on all responses: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Strict-Transport-Security` (HTTPS only)
- CSRF protection on all form endpoints
- `Secure` cookie flag derived from `KAUTH_BASE_URL` at startup
- Startup validation — server refuses to start if `KAUTH_ENV=production` and unsafe defaults are detected
- Graceful shutdown — in-flight requests complete within a 5-second window before the server exits

### Deployment

- Multi-stage Docker build — ~120 MB runtime image (eclipse-temurin:17-jre)
- Flyway versioned migrations — run automatically on startup
- Docker Compose setup included
- Minimum requirements: 512 MB RAM, 1 vCPU, PostgreSQL 14+
- Published to GitHub Container Registry: `ghcr.io/inumansoul/kotauth:1.0.0`

---

## Integration Guides

- [React SPA with TanStack Router](docs/guides/react-spa-tanstack-router.md) — end-to-end OIDC with `oidc-client-ts`, auth guards, and silent token refresh
- [Environment Variable Reference](docs/ENV_REFERENCE.md) — every variable with defaults and production guidance

---

## Architecture

Hexagonal (Ports & Adapters) throughout. The domain layer has zero framework imports — all I/O is behind typed port interfaces. Every service is independently unit-testable against in-memory fakes with no database or HTTP required.

Key architectural decisions: per-tenant RS256 keys, append-only audit log with separate write/read paths, sealed result types instead of exceptions for business failures, PKCE required for public clients. The full ADR table is in [docs/ROADMAP.md](docs/ROADMAP.md#architecture-decisions).

**Tech stack:** Kotlin · Ktor 2 · JVM 17 · PostgreSQL 15 · Exposed ORM · Flyway · RS256 JWT · bcrypt · AES-256-GCM

---

## What's Not in v1.0

Explicit scope decisions, not gaps:

- **LDAP / Active Directory sync** — planned for Phase 6
- **SAML 2.0** — planned for Phase 6
- **WebAuthn / Passkeys** — planned for Phase 7
- **Prometheus metrics** — planned for Phase 8
- **Helm chart** — planned for Phase 8
- **SCIM 2.0** — planned for Phase 9
- **arm64 Docker image** — dropped in v1.0 due to QEMU build time; will be added with native runners

---

## Known Compiler Warnings

The build produces several non-blocking Kotlin warnings (`Name shadowed`, unused parameters in route handlers). These do not affect runtime behavior and will be addressed in a follow-up patch.

---

## Post-V1 Roadmap

Phases 6–9 cover enterprise federation (LDAP, SAML, external OIDC broker), advanced authentication (WebAuthn, magic links), observability (Prometheus, structured logs, Helm), and platform expansion (SCIM 2.0, SDK, audit export). Full details in [docs/ROADMAP.md](docs/ROADMAP.md).
