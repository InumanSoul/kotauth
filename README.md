# Kotauth

[![CI](https://github.com/inumansoul/kotauth/actions/workflows/ci.yml/badge.svg)](https://github.com/inumansoul/kotauth/actions/workflows/ci.yml)
[![Docker Image](https://img.shields.io/badge/ghcr.io-kotauth-blue?logo=docker)](https://ghcr.io/inumansoul/kotauth)
[![Latest Release](https://img.shields.io/github/v/release/inumansoul/kotauth)](https://github.com/inumansoul/kotauth/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> Identity infrastructure for modern applications. Self-hosted, container-native, developer-first.

Kotauth is an open-source authentication and identity platform that bridges the gap between enterprise IAM systems (Keycloak, Okta) and developer-friendly SaaS tools (Clerk, Auth0). Full OAuth2/OIDC compliance. Runs in Docker. Up in minutes.

**[Live demo](https://demo.kotauth.com)** · **[Documentation](https://kotauth.com)** · **[Roadmap](docs/ROADMAP.md)**

---

## Try it — one command

You need Docker and Docker Compose. Nothing else.

```bash
curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.yml
docker compose up -d
```

Open **http://localhost:8080/admin** — demo data is pre-loaded with two workspaces, users, roles, and applications. Credentials are shown in the banner.

For configuration knobs (set your own `KAUTH_SECRET_KEY`, point at an external DB, enable Redis), see the [quickstart guide](docs/deploy/quickstart.md).

---

## Build from source

For contributors or anyone who wants to run from the cloned repo.

```bash
git clone https://github.com/inumansoul/kotauth.git
cd kotauth
make up
```

`make up` builds the image from the local Dockerfile and starts the full stack. Run `make help` for the rest (test, lint, logs, nuke, …). For the fast inner loop — host JVM against Docker-hosted Postgres + Redis — use `make run`. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full developer guide.

---

## Docker images

Images are published to GitHub Container Registry on every tagged release.

| Tag | Description |
|---|---|
| `ghcr.io/inumansoul/kotauth:latest` | Latest stable release |
| `ghcr.io/inumansoul/kotauth:1` | Latest patch in the `1.x` line |
| `ghcr.io/inumansoul/kotauth:1.1` | Latest patch in `1.1.x` |
| `ghcr.io/inumansoul/kotauth:1.1.2` | Exact version pin |

Pre-release tags (e.g. `1.1.0-rc1`) are published but do not move the `latest` or major/minor tags.

```bash
docker pull ghcr.io/inumansoul/kotauth:latest
```

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
- **Security** — bcrypt passwords, AES-256-GCM secrets at rest, rate limiting on login/register/token endpoints (IP-based), security response headers, per-tenant RS256 key pairs

---

## Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `KAUTH_BASE_URL` | **Yes** | — | Public base URL. Used in OIDC tokens and discovery docs. Must be `https://` in production. |
| `KAUTH_SECRET_KEY` | Recommended | Random (ephemeral) | 32+ char hex string. Used for AES-256-GCM encryption and session signing. If not set, SMTP config is unavailable and sessions don't survive restarts. |
| `KAUTH_ENV` | No | `development` | Set to `production` to enable HTTPS enforcement and strict startup validation. |
| `KAUTH_DEMO_MODE` | No | `false` | Set to `true` to seed demo data and show a demo banner. For showcase deployments. |
| `DB_URL` | No | Auto-constructed | PostgreSQL JDBC URL. When not set, constructed from `DB_HOST`, `DB_PORT`, and `DB_NAME`. Set directly for external/managed databases (RDS, Supabase, Neon). |
| `DB_USER` | **Yes** | — | PostgreSQL username. |
| `DB_PASSWORD` | **Yes** | — | PostgreSQL password. |

For the full reference including per-tenant SMTP and security policy configuration, see [docs/ENV_REFERENCE.md](docs/ENV_REFERENCE.md).

---

## Production deployment

The full walkthrough — TLS via Caddy, external database, Redis sidecar, file-based secrets, backups, upgrades, security checklist — is in [`docs/deploy/production.md`](docs/deploy/production.md).

The shape of it:

```bash
mkdir kotauth && cd kotauth
curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.prod.yml
curl --create-dirs -o docker/Caddyfile \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/Caddyfile

# fill in .env: KAUTH_BASE_URL, KAUTH_SECRET_KEY, DB_PASSWORD, DOMAIN, ACME_EMAIL
docker compose -f docker-compose.prod.yml up -d
```

Minimum requirements: 512 MB RAM, 1 vCPU, PostgreSQL 14+. Already have a managed Postgres? Set `DB_URL` in `.env` — Kotauth uses it directly. To enable the Redis sidecar, add `--profile redis`.

For a public demo deployment (seeded workspaces + reset cron), set `KAUTH_DEMO_MODE=true` — see [`docs/deploy/production.md#11-demo-deployment`](docs/deploy/production.md#11-demo-deployment).

---

## Integration guides

- [Quickstart](docs/deploy/quickstart.md) — local evaluation
- [Production deployment](docs/deploy/production.md) — TLS, backups, upgrades
- [React SPA with TanStack Router](docs/guides/react-spa-tanstack-router.md)
- Generic OIDC *(coming soon)*

---

## API reference

Swagger UI is available at:

```
http://localhost:8080/api/docs
```

The raw OpenAPI 3.1 spec is at `src/main/resources/openapi/v1.yaml`.

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

Key decisions are documented as ADRs in [docs/adr/](docs/adr/).

---

## Tech stack

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
