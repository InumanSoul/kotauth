# Kotauth

[![CI](https://github.com/inumansoul/kotauth/actions/workflows/ci.yml/badge.svg)](https://github.com/inumansoul/kotauth/actions/workflows/ci.yml)
[![Docker Image](https://img.shields.io/badge/ghcr.io-kotauth-blue?logo=docker)](https://ghcr.io/inumansoul/kotauth)
[![Latest Release](https://img.shields.io/github/v/release/inumansoul/kotauth)](https://github.com/inumansoul/kotauth/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> Identity infrastructure for modern applications. Self-hosted, container-native, developer-first.

Kotauth is an open-source authentication and identity platform that bridges the gap between enterprise IAM (Keycloak, Okta) and developer-friendly SaaS (Clerk, Auth0). Full OAuth2/OIDC compliance. Multi-tenant. Runs in Docker. Up in minutes.

**[Live demo](https://demo.kotauth.com)** · **[Documentation](https://kotauth.com)** · **[Roadmap](docs/ROADMAP.md)**

---

## Try it

You need Docker and Docker Compose. Nothing else.

```bash
curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.yml
docker compose up -d
```

Open **http://localhost:8080/admin**. Demo data is pre-loaded with two workspaces, users, roles, and applications; credentials are shown in the banner.

For configuration knobs — set your own `KAUTH_SECRET_KEY`, point at an external database, enable Redis — see the [quickstart guide](docs/deploy/quickstart.md).

---

## Features

- **OAuth2 / OIDC provider** — Authorization Code + PKCE, Client Credentials, refresh token rotation, token introspection & revocation, RFC 8707 resource indicators
- **Multi-tenancy** — Isolated workspaces, each with its own users, applications, settings, and RS256 signing keys
- **RBAC** — Roles, groups, composite role inheritance, JWT `realm_access` / `resource_access` claims
- **MFA** — TOTP (RFC 6238), recovery codes, per-tenant policy (optional / required / required for admins)
- **Sign-in identifier** — per-workspace choice of username, email, or either; existing workspaces keep username-only until an admin opts in
- **Social login** — Google and GitHub OAuth2, with automatic account linking
- **OIDC identity brokering** — sign users in through any OpenID Connect provider, configured per workspace; endpoints read from the issuer's discovery document, with optional per-endpoint pins. Optional just-in-time account creation, off by default and gated on a provider-asserted verified email plus an exact-match allowed-domain list. No identity provider has been verified against a live tenant; the implementation follows the specifications the providers publish
- **User self-service** — Email verification, password reset, session management, MFA enrollment
- **Admin console** — Web UI for workspaces, users, applications, audit logs, webhooks, branding
- **REST API v1** — 30+ endpoints, API key authentication, OpenAPI 3.1 spec with Swagger UI
- **SCIM 2.0 provisioning** — `/Users` and `/Groups` per workspace, targeting RFC 7644 over the RFC 7643 schema; per-key wire dialects normalise the deviations major identity providers document. No verification against, or certification for, any particular identity product is claimed
- **Webhooks** — HMAC-signed event delivery with exponential backoff retry
- **Audit logging** — 30+ immutable event types with per-tenant HMAC chain, queryable via API and admin UI
- **Security** — bcrypt passwords, AES-256-GCM secrets at rest, sliding-window rate limiting, security response headers, per-tenant RS256 key pairs, file-based secret injection (`*_FILE`)

---

## Integrate your app

| Pattern | When to use | Guide |
|---|---|---|
| **React SPA — browser-direct OIDC** | Internal tools, small apps, no separate backend | [docs/guides/react-spa-direct.md](docs/guides/react-spa-direct.md) |
| **React SPA — BFF pattern** | Production-grade, no tokens in JavaScript | [docs/guides/react-bff-pattern.md](docs/guides/react-bff-pattern.md) |
| **TanStack Router route guards** | Composes on top of either pattern above | [docs/guides/react-spa-tanstack-router.md](docs/guides/react-spa-tanstack-router.md) |
| **Any OIDC client library** | Generic OIDC consumer — discovery at `/t/<workspace>/.well-known/openid-configuration` | *(coming soon)* |

The full REST API is documented at `http://localhost:8080/api/docs` (Swagger UI); the raw OpenAPI 3.1 spec lives at `src/main/resources/openapi/v1.yaml`.

---

## Deploy

**Production** — TLS via Caddy, external database, Redis sidecar, backups, upgrades, security checklist: [docs/deploy/production.md](docs/deploy/production.md).

```bash
mkdir kotauth && cd kotauth
curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.prod.yml
curl --create-dirs -o docker/Caddyfile \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/Caddyfile
# fill in .env: KAUTH_BASE_URL, KAUTH_SECRET_KEY, DB_PASSWORD, DOMAIN, ACME_EMAIL
docker compose -f docker-compose.prod.yml up -d
```

Minimum requirements: 512 MB RAM, 1 vCPU, PostgreSQL 14+. Managed Postgres? Set `DB_URL` and Kotauth uses it directly. Redis sidecar? Add `--profile redis`.

**Build from source** — `git clone … && make up` builds the image from the local Dockerfile and starts the full stack. See [CONTRIBUTING.md](CONTRIBUTING.md) for the inner loop (`make run` boots the JVM on the host against Docker-hosted Postgres for sub-second restarts).

**Docker images** are published to GitHub Container Registry on every tagged release:

| Tag | Description |
|---|---|
| `ghcr.io/inumansoul/kotauth:latest` | Latest stable release |
| `ghcr.io/inumansoul/kotauth:1` | Latest patch in the `1.x` line |
| `ghcr.io/inumansoul/kotauth:1.19` | Latest patch in `1.19.x` |
| `ghcr.io/inumansoul/kotauth:1.19.2` | Exact version pin |

Pre-release tags (e.g. `1.19.0-rc1`) are published but do not move the `latest` or major/minor tags. The full env-variable reference is at [docs/ENV_REFERENCE.md](docs/ENV_REFERENCE.md).

---

## Under the hood

**Stack:** Kotlin 2.3.20, Ktor 3.4.2, Exposed 0.61.0 (ORM), PostgreSQL 15, JVM 17, Gradle 8.14. The runtime image is ~120 MB; the JAR runs as an unprivileged user.

**Architecture:** [hexagonal (Ports & Adapters)](https://alistair.cockburn.us/hexagonal-architecture/) — the domain layer (`domain/model`, `domain/port`, `domain/service`) has zero framework dependencies, so business logic is testable in-memory without Docker, a database, or HTTP. Adapters (`adapter/web`, `adapter/persistence`, `adapter/token`, `adapter/email`, `adapter/social`) sit at the edge.

**Multi-tenancy** maps the standard IAM vocabulary to a workspace-first model: a **Workspace** is a Realm/Tenant; an **Application** is an OAuth2 Client; a **User** is an Identity scoped to one workspace. Workspaces are fully isolated — the same email can exist in many workspaces independently.

Architectural decisions are recorded as ADRs in [docs/adr/](docs/adr/). Consult them before changing patterns like the migration strategy, sealed result types, audit logging chain, or secret hashing.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and PRs welcome.

## License

[MIT](LICENSE)
