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
curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.quickstart.yml
docker compose -f docker-compose.quickstart.yml up -d
```

Open **http://localhost:8080/admin** — demo data is pre-loaded with two workspaces, users, roles, and applications. Credentials are shown in the banner.

That's it. When you're ready to customize, see the next section.

---

## Quickstart — configure your own instance

For running Kotauth with your own settings. No repo clone required.

**1. Grab the compose file and env template**

```bash
mkdir kotauth && cd kotauth
curl --create-dirs -o docker/docker-compose.yml \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/docker-compose.yml
curl -o .env.example \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/.env.example
cp .env.example .env
```

**2. Set your secret key**

Open `.env` and generate a secret key:

```bash
# In .env:
KAUTH_SECRET_KEY=$(openssl rand -hex 32)
```

`KAUTH_BASE_URL` defaults to `http://localhost:8080`. Change it if deploying remotely.

**3. Start**

```bash
docker compose -f docker/docker-compose.yml up -d
```

Kotauth starts on port `8080`. PostgreSQL is bundled — no separate database setup. Flyway migrations run automatically on first boot.

**4. Open the admin console**

```
http://localhost:8080/admin
```

Master workspace admin credentials are printed in the startup log on first run. Change them immediately:

```bash
docker compose -f docker/docker-compose.yml logs kotauth | grep "Admin credentials"
```

**5. Create a workspace**

In the admin console, create a new workspace (e.g. `my-app`). This is your tenant — it gets its own users, OAuth clients, and signing keys. Your OIDC discovery document is then live at:

```
http://localhost:8080/t/my-app/.well-known/openid-configuration
```

---

## Build from source

For contributors or anyone who wants to run from the cloned repo.

```bash
git clone https://github.com/inumansoul/kotauth.git
cd kotauth
cp .env.example .env
# Edit .env: set KAUTH_SECRET_KEY
make up
```

`make up` builds the image from the local Dockerfile via `docker-compose.dev.yml`. Run `make help` to see all available targets (test, lint, logs, nuke, etc.). See [CONTRIBUTING.md](CONTRIBUTING.md) for the full developer guide.

---

## Docker images

Images are published to GitHub Container Registry on every tagged release.

| Tag | Description |
|---|---|
| `ghcr.io/inumansoul/kotauth:latest` | Latest stable release |
| `ghcr.io/inumansoul/kotauth:1` | Latest patch in the `1.x` line |
| `ghcr.io/inumansoul/kotauth:1.1` | Latest patch in `1.1.x` |
| `ghcr.io/inumansoul/kotauth:1.1.1` | Exact version pin |

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

## Bring your own database

Already have PostgreSQL (or using a managed provider like RDS, Supabase, Neon)? Skip the bundled database entirely:

```bash
docker compose -f docker/docker-compose.external-db.yml up -d
```

Set these in `.env`:

```bash
DB_URL=jdbc:postgresql://your-host:5432/kotauth_db?sslmode=require
DB_USER=kotauth
DB_PASSWORD=your-password
KAUTH_BASE_URL=https://auth.yourdomain.com
KAUTH_SECRET_KEY=$(openssl rand -hex 32)
```

Flyway runs all migrations automatically — just point it at an empty database.

For production with TLS, layer the Caddy overlay on top:

```bash
docker compose -f docker/docker-compose.external-db.yml -f docker/docker-compose.prod.yml up -d
```

---

## Production deployment

See the full guide: [docs/guides/production-deployment.md](docs/guides/production-deployment.md).

The short version: TLS is required. Use `docker/docker-compose.prod.yml` which adds a Caddy sidecar for automatic Let's Encrypt certificates.

```bash
# Bundled database + Caddy TLS
# Requires: DOMAIN and ACME_EMAIL set in .env, ports 80/443 open
docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod.yml up -d
```

Minimum requirements: 512 MB RAM, 1 vCPU, PostgreSQL 14+.

---

## Demo deployment

Deploy a public showcase instance with pre-populated workspaces, users, roles, and applications. Ideal for `demo.yourdomain.com`.

```bash
# Set KAUTH_DEMO_MODE=true in .env (or use the demo overlay)
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.prod.yml \
  -f docker/docker-compose.demo.yml \
  up -d
```

The demo overlay sets `KAUTH_DEMO_MODE=true`, which seeds two workspaces ("Acme Corp" and "Startup Labs") with realistic data on startup and shows a credentials banner on every page. An hourly cron with `docker compose down -v && up -d` resets the data. See [docs/ENV_REFERENCE.md](docs/ENV_REFERENCE.md#demo-mode) for details.

---

## Integration guides

- [React SPA with TanStack Router](docs/guides/react-spa-tanstack-router.md)
- [Production deployment](docs/guides/production-deployment.md)
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
