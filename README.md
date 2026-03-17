# Kotauth

> Identity infrastructure for modern applications. Self-hosted, container-native, developer-first.

Kotauth is an open-source authentication and identity platform that bridges the gap between enterprise IAM systems (Keycloak, Okta) and developer-friendly SaaS tools (Clerk, Auth0). Full OAuth2/OIDC compliance. Runs in Docker. Up in minutes.

---

## Features

- **OAuth2 / OIDC provider** — Authorization Code + PKCE, Client Credentials, refresh token rotation, token introspection & revocation
- **Multi-tenancy** — Isolated workspaces, each with its own users, apps, settings, and RS256 signing keys
- **RBAC** — Roles, groups, composite role inheritance, JWT `realm_access` / `resource_access` claims
- **MFA** — TOTP (RFC 6238), recovery codes, per-tenant policy (optional / required / required for admins)
- **Social login** — Google and GitHub OAuth2, with automatic account linking
- **User self-service** — Email verification, password reset, session management, MFA enrollment
- **Admin console** — Full web UI for workspace settings, users, applications, audit logs, webhooks
- **REST API v1** — 30+ endpoints, API key authentication, OpenAPI 3.1 spec with Swagger UI
- **Webhooks** — HMAC-signed event delivery with exponential backoff retry
- **Audit logging** — 30+ immutable event types, append-only, queryable via API and admin UI
- **Security** — bcrypt passwords, AES-256-GCM secrets at rest, rate limiting, per-tenant RS256 key pairs

---

## Quickstart

You need Docker and Docker Compose. Nothing else.

**1. Clone the repo**

```bash
git clone https://github.com/your-org/kotauth.git
cd kotauth
```

**2. Create your `.env` file**

```bash
cp .env.example .env
```

The defaults work for local development. For a custom secret key:

```bash
echo "KAUTH_SECRET_KEY=$(openssl rand -hex 32)" >> .env
echo "KAUTH_BASE_URL=http://localhost:8080" >> .env
```

**3. Start**

```bash
docker compose up
```

Kotauth starts on port `8080`. Database migrations run automatically on first boot.

**4. Open the admin console**

```
http://localhost:8080/admin
```

Default master workspace credentials are printed in the startup log on first run. Change them immediately.

**5. Create a workspace**

In the admin console, create a new workspace (e.g. `my-app`). This is your tenant — it gets its own users, OAuth clients, and signing keys.

That's it. Your OIDC discovery document is live at:

```
http://localhost:8080/t/my-app/.well-known/openid-configuration
```

---

## Concepts

Kotauth maps IAM complexity to five concepts:

| Kotauth | Traditional IAM equivalent |
|---|---|
| **Workspace** | Realm / Tenant |
| **Application** | OAuth2 Client |
| **User** | Identity / Principal |
| **Role / Group** | Role / Policy |
| **API Key** | Service credential |

Each workspace is a fully isolated identity directory. The same email address can exist in multiple workspaces — they are completely independent.

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `KAUTH_BASE_URL` | **Yes** | — | Public base URL. Used in OIDC tokens and discovery docs. Must be `https://` in production. |
| `KAUTH_SECRET_KEY` | Recommended | Random (ephemeral) | 32+ char hex string. Used for AES-256-GCM encryption and session signing. If not set, SMTP config is unavailable and sessions don't survive restarts. |
| `KAUTH_ENV` | No | `development` | Set to `production` to enable HTTPS enforcement and strict startup validation. |
| `DB_URL` | **Yes** | — | PostgreSQL JDBC URL. Example: `jdbc:postgresql://db:5432/kotauth_db` |
| `DB_USER` | **Yes** | — | PostgreSQL username. |
| `DB_PASSWORD` | **Yes** | — | PostgreSQL password. |

For the full reference including per-tenant SMTP and security policy configuration, see [docs/ENV_REFERENCE.md](docs/ENV_REFERENCE.md).

---

## Production Deployment

**Minimum requirements:** 512 MB RAM, 1 vCPU, PostgreSQL 14+.

**TLS is required for production.** Kotauth expects TLS to be terminated by a reverse proxy (nginx, Caddy, Traefik). Set `KAUTH_ENV=production` and ensure `KAUTH_BASE_URL` starts with `https://` — the server will refuse to start otherwise.

Example with Caddy:

```
auth.yourdomain.com {
    reverse_proxy kotauth:8080
}
```

Then in your `.env`:

```
KAUTH_BASE_URL=https://auth.yourdomain.com
KAUTH_ENV=production
KAUTH_SECRET_KEY=<openssl rand -hex 32>
```

---

## Integration Guides

- [React SPA with TanStack Router](docs/guides/react-spa-tanstack-router.md)
- Generic OIDC *(coming soon)*

---

## API Reference

Swagger UI is available at:

```
http://localhost:8080/t/{workspace-slug}/api/v1/docs
```

The raw OpenAPI 3.1 spec is at `src/main/resources/openapi/v1.yaml`.

---

## Architecture

Kotauth is built on [hexagonal architecture](https://alistair.cockburn.us/hexagonal-architecture/) (Ports & Adapters). The domain layer has zero framework dependencies — all I/O goes through typed port interfaces.

```
domain/
  model/      — Pure data classes (User, Tenant, Session, …)
  port/       — Interface contracts (TenantRepository, EmailPort, …)
  service/    — Business logic (AuthService, OAuthService, MfaService, …)

adapter/
  web/        — Ktor HTTP routes
  persistence/— PostgreSQL + Exposed ORM
  token/      — JWT signing, password hashing
  email/      — SMTP delivery
  social/     — Google / GitHub OAuth adapters

infrastructure/
              — Cross-cutting: key provisioning, rate limiting, encryption
```

Key decisions are documented as ADRs in [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md#architecture-decision-records).

---

## Tech Stack

- **Runtime:** Kotlin, Ktor 2, JVM 17
- **Database:** PostgreSQL 15, Exposed ORM, Flyway migrations
- **Tokens:** RS256 JWT (per-tenant key pairs), bcrypt, AES-256-GCM
- **Container:** Multi-stage Docker build, ~120 MB runtime image

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## License

[MIT](LICENSE)
