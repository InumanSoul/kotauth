# Environment Variable Reference

All environment variables Kotauth reads at startup. Variables marked **Required** will cause a fatal startup error if missing. Variables marked **Recommended** degrade functionality if absent but do not block startup.

---

## File-based secret injection (`*_FILE`)

For every secret listed below, Kotauth also accepts a sibling `<NAME>_FILE` variable containing a filesystem path. At startup, the file's contents are read, trimmed (Docker secrets usually carry a trailing newline), and used as the secret value. `<NAME>_FILE` takes precedence over `<NAME>` when both are set.

This is compatible with Docker Swarm secrets, Kubernetes mounted secrets, and systemd `LoadCredential=` — none of which expose secret values through process environment.

Supported variables:
- `KAUTH_SECRET_KEY_FILE`
- `DB_PASSWORD_FILE`
- `KAUTH_REDIS_PASSWORD_FILE`
- `KAUTH_BOOTSTRAP_ADMIN_PASSWORD_FILE`
- `KAUTH_BOOTSTRAP_API_KEYS_FILE`

Example (Docker Swarm / Compose):

```yaml
secrets:
  kauth_secret_key:
    external: true

services:
  app:
    secrets:
      - kauth_secret_key
    environment:
      KAUTH_SECRET_KEY_FILE: /run/secrets/kauth_secret_key
```

See [`docs/deploy/production.md#7-file-based-secrets`](deploy/production.md#7-file-based-secrets) for a working Docker Secrets example.

---

## Core

### `KAUTH_BASE_URL`
**Required.**

Public base URL. Used as the OIDC issuer (`iss` claim), in discovery documents, redirect-URI validation, and email links. Must be `https://` in production; `http://localhost` is allowed in development. No trailing slash.

```
KAUTH_BASE_URL=https://auth.yourdomain.com
```

---

### `KAUTH_ENV`
**Optional.** Default: `development`

Controls startup validation strictness.

| Value | Behavior |
|---|---|
| `development` | Lax: HTTP allowed, default secrets tolerated, warnings printed |
| `production` | Strict: HTTPS required, quickstart secret rejected, strict cookie flags |

```
KAUTH_ENV=production
```

---

### `KAUTH_SECRET_KEY`
**Required.**

32+ character secret used for AES-256-GCM encryption of secrets at rest (SMTP credentials, TOTP secrets, RSA private keys) and HMAC-SHA256 signing of session cookies. Generate: `openssl rand -hex 32`. Also accepts `KAUTH_SECRET_KEY_FILE` (see [File-based secret injection](#file-based-secret-injection-_file)).

```
KAUTH_SECRET_KEY=<openssl rand -hex 32 output>
```

---

## Database

### `DB_URL`
**Optional override.**

Full PostgreSQL JDBC connection URL. When set, takes full precedence — `DB_HOST`, `DB_PORT`, and `DB_NAME` are ignored.

Use this to connect to an external or managed database, or when you need to append JDBC parameters such as SSL mode:

```
# External / managed database with SSL
DB_URL=jdbc:postgresql://your-host:5432/kotauth_db?sslmode=require
```

When `DB_URL` is not set, the compose stack constructs the URL automatically from `DB_HOST`, `DB_PORT`, and `DB_NAME` (see below). Kotauth runs Flyway migrations on startup — the schema is created automatically.

---

### `DB_HOST`
**Optional.** Default (in Docker Compose): `db`

Hostname of the PostgreSQL server. Used to construct `DB_URL` when `DB_URL` is not explicitly set.

```
# Bundled db service (default)
DB_HOST=db

# External database
DB_HOST=xxx.rds.amazonaws.com
```

---

### `DB_PORT`
**Optional.** Default: `5432`

Port of the PostgreSQL server. Used to construct `DB_URL` when `DB_URL` is not explicitly set.

```
DB_PORT=5432
```

Common non-default ports: `6432` (PgBouncer), `5433` (non-standard local instance).

---

### `DB_NAME`
**Optional.** Default (in Docker Compose): `kotauth_db`

Database name. Used to construct `DB_URL` when `DB_URL` is not explicitly set, and to initialize the bundled `db` service.

```
DB_NAME=kotauth_db
```

---

### `DB_USER`
**Required.**

PostgreSQL username.

```
DB_USER=kotauth
```

---

### `DB_PASSWORD`
**Required.**

PostgreSQL password. Also accepts `DB_PASSWORD_FILE` (see [File-based secret injection](#file-based-secret-injection-_file)).

```
DB_PASSWORD=changeme
```

---

### `KAUTH_TRUSTED_PROXY`
**Optional.** Default: `false`

When `true`, Kotauth trusts `X-Forwarded-For` / `X-Forwarded-Proto` headers for client-IP resolution. **Only enable behind a reverse proxy that overwrites these headers** — on a directly-exposed instance, this lets clients spoof their IP to bypass per-IP rate limits on login, token, MFA, and OTP endpoints. The bundled Caddy production setup sets it automatically.

```
KAUTH_TRUSTED_PROXY=true
```

---

### `DB_POOL_MAX_SIZE`
**Optional.** Default: `10`

Maximum HikariCP pool size. When running multiple Kotauth instances, ensure the total stays within PostgreSQL's `max_connections` (default 100).

```
DB_POOL_MAX_SIZE=10
```

---

### `DB_POOL_MIN_IDLE`
**Optional.** Default: `2`

Minimum idle connections kept warm.

```
DB_POOL_MIN_IDLE=2
```

---

## Redis

Optional sidecar for distributed rate limiting and sessions. Single-instance deployments can leave these unset. See [REDIS.md](REDIS.md) for the operator guide.

### `KAUTH_REDIS_URL`
**Optional.** Default: _unset_ (Redis disabled — in-memory limiter, Postgres sessions)

Setting this turns Redis on. Must start with `redis://` or `rediss://` (TLS). Anything else fails fast at startup.

```
KAUTH_REDIS_URL=redis://redis:6379
KAUTH_REDIS_URL=rediss://redis.internal:6380
```

When set, the server runs a `PING` probe at startup; an unreachable Redis exits with a `FATAL` banner rather than silently degrading to per-replica limits.

---

### `KAUTH_REDIS_USERNAME`
**Optional.** Default: _unset_

Redis 6+ ACL username. Omit if your Redis only requires a password.

---

### `KAUTH_REDIS_PASSWORD`
**Optional.** Default: _unset_

Redis password. When `KAUTH_REDIS_USERNAME` is unset, this is sent as the `default`-user credential — works for both Redis 5 (legacy `requirepass`) and Redis 6+ (default ACL user).

Also accepts `KAUTH_REDIS_PASSWORD_FILE` (see [File-based secret injection](#file-based-secret-injection-_file)).

---

### `KAUTH_REDIS_TIMEOUT_MS`
**Optional.** Default: `250`

Connection-level timeout (ms) for the Lettuce client.

---

### `KAUTH_REDIS_COMMAND_TIMEOUT_MS`
**Optional.** Default: `100`

Per-command ceiling (ms) on the auth hot path. Don't raise without measuring — the rate-limit check sits on every login round-trip and the timeout bounds how long a request thread can block on a sick Redis.

---

### `KAUTH_REDIS_STARTUP_PROBE_TIMEOUT_MS`
**Optional.** Default: `2000`

Timeout (ms) for the `PING` probe at startup. Higher in slow networks; lower if you want the gate to fail faster.

---

## OIDC SSO

The two knobs below tune the witness cookie that drives silent SSO across clients on the same tenant. See [ADR-13](adr/ADR-13-oidc-sso-witness-cookie.md) for the design and threat model.

### `KAUTH_SSO_SESSION_TTL_SECONDS`
**Optional.** Default: `86400` (24 hours)

How long the `KOTAUTH_SSO` cookie lives. Determines the maximum interval over which a user can silent-auth across clients without re-proving credentials. Auth0's default is 24h; Keycloak defaults to 36000s (10h). Lower values trade SSO convenience for tighter session-freshness guarantees.

```
KAUTH_SSO_SESSION_TTL_SECONDS=86400
```

The cookie carries its own `expiresAt` timestamp in the signed payload, so this TTL cannot be silently exceeded by a stale cookie minted before the operator tightened the value.

---

### `KAUTH_SSO_SESSION_MAX_TTL_SECONDS`
**Optional.** Default: `2592000` (30 days)

Operator ceiling on `KAUTH_SSO_SESSION_TTL_SECONDS`. The server refuses to start unless `TTL ≤ MAX_TTL` and both are `≥ 60`.

```
KAUTH_SSO_SESSION_MAX_TTL_SECONDS=2592000
```

---

## Demo Mode

### `KAUTH_DEMO_MODE`
**Optional.** Default: `false`

When `true`, seeds two demo workspaces (`Acme Corp`, `Startup Labs`) with users, applications, roles, groups, webhooks, and audit log entries, and shows a sticky credential banner. Seed is idempotent. Intended for public showcase deployments paired with an hourly reset (see [Demo deployment](#example-env--demo-deployment)).

```
KAUTH_DEMO_MODE=true
```

**Seeded credentials:**

| Workspace | Username | Password |
|---|---|---|
| Master (admin console) | `admin` | `Demo1234!` |
| Acme Corp | `sarah.chen` | `Demo1234!` |
| Startup Labs | `jordan.lee` | `Demo1234!` |

| M2M client (`client_credentials`) | Client ID | Client secret |
|---|---|---|
| Acme Dashboard | `acme-dashboard` | `DemoM2M1234!` |

---

## Admin Bootstrap

### `KAUTH_BOOTSTRAP_ADMIN_PASSWORD`
**Optional.** Default: _unset_

The initial password assigned to the seeded `admin` user when Kotauth boots against a fresh database. The seeded admin can sign in directly with this password and rotate it from Profile → Security after first login — no token-redemption flow is required.

Resolution order:

1. **`KAUTH_BOOTSTRAP_ADMIN_PASSWORD` set** — used as-is. Must be at least 12 characters and contain upper, lower, and digit; otherwise startup fails.
2. **`KAUTH_DEMO_MODE=true`** — uses the documented demo password (`Demo1234!`). Demo mode is rejected in production by the secret-key check.
3. **Otherwise** — Kotauth generates a 128-bit random password and prints it once to **stdout** at boot inside a clearly-framed banner. Capture the log on first run and store the password.

```
KAUTH_BOOTSTRAP_ADMIN_PASSWORD=<a strong operator-chosen password>
```

The legacy default `changeme123!` is removed. Operators who scripted around it must either set this variable or read the random password from the first-boot log.

Also accepts `KAUTH_BOOTSTRAP_ADMIN_PASSWORD_FILE` (see [File-based secret injection](#file-based-secret-injection-_file)).

---

## Legacy / Internal

### `JWT_SECRET`
**Deprecated.** Not used for token signing (Kotauth uses RS256 with per-tenant key pairs). Only checked in production mode to reject the known-insecure default value `secret-key-12345`. Do not set this in new deployments.

---

## Per-Tenant Settings (Admin Console)

These are not environment variables — they are configured per workspace through the admin console UI or the Management API. Documented here for reference.

### Token Lifetimes
- **Access token TTL** — Default: 300 seconds (5 min). Override per-application.
- **Refresh token TTL** — Default: 86400 seconds (24 hours).
- **Email verification token TTL** — Default: 24 hours.
- **Password reset token TTL** — Default: 1 hour.

### Password Policy
- Minimum length (default: 8)
- Require uppercase / lowercase / numbers / symbols
- Maximum age in days (0 = no expiry)
- Password history depth (0 = no history check)

### MFA Policy
- `optional` — Users can enroll but are not required to
- `required` — All users must complete MFA before accessing the portal
- `required_for_admins` — Only users with the `admin` role are required to enroll

### SMTP Configuration
- Host, port, username, password (AES-256-GCM encrypted at rest)
- From address and display name
- TLS mode: `NONE`, `STARTTLS`, `SSL`

---

## Example `.env` — Local development

Copy `.env.example` to `.env` and fill in `KAUTH_SECRET_KEY`. DB credentials are handled by the bundled compose stack.

```env
KAUTH_BASE_URL=http://localhost:8080
KAUTH_ENV=development
KAUTH_SECRET_KEY=        # generate: openssl rand -hex 32

DB_HOST=db
DB_PORT=5432
DB_NAME=kotauth_db
DB_USER=kotauth
DB_PASSWORD=changeme     # fine for local dev, change for production
```

## Example `.env` — Production (bundled PostgreSQL)

```env
KAUTH_BASE_URL=https://auth.yourdomain.com
KAUTH_ENV=production
KAUTH_SECRET_KEY=        # generate: openssl rand -hex 32   ← never skip this

DB_HOST=db
DB_PORT=5432
DB_NAME=kotauth_db
DB_USER=kotauth
DB_PASSWORD=             # use a strong, unique password

# Required when using docker-compose.prod.yml (Caddy TLS)
DOMAIN=auth.yourdomain.com
ACME_EMAIL=you@yourdomain.com
```

## Example `.env` — Production (external / managed database)

```env
KAUTH_BASE_URL=https://auth.yourdomain.com
KAUTH_ENV=production
KAUTH_SECRET_KEY=        # generate: openssl rand -hex 32

# DB_URL overrides DB_HOST / DB_PORT / DB_NAME entirely
DB_URL=jdbc:postgresql://your-managed-host:5432/kotauth_db?sslmode=require
DB_USER=kotauth
DB_PASSWORD=             # use a strong, unique password
```

## Example `.env` — Demo deployment

```env
KAUTH_BASE_URL=https://demo.kotauth.com
KAUTH_ENV=production
KAUTH_SECRET_KEY=<any value — data is ephemeral>
KAUTH_DEMO_MODE=true

DB_NAME=kotauth_db
DB_USER=kotauth
DB_PASSWORD=demo

DOMAIN=demo.kotauth.com
ACME_EMAIL=you@yourdomain.com
```

Start:

```bash
docker compose -f docker-compose.prod.yml up -d
```

Hourly reset cron (wipes the database volume and re-seeds on restart):

```bash
0 * * * * cd /opt/kotauth && docker compose -f docker-compose.prod.yml down -v && docker compose -f docker-compose.prod.yml up -d
```

See [docs/deploy/production.md](deploy/production.md) for the full deployment walkthrough.
