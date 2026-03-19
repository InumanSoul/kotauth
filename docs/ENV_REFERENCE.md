# Environment Variable Reference

All environment variables Kotauth reads at startup. Variables marked **Required** will cause a fatal startup error if missing. Variables marked **Recommended** degrade functionality if absent but do not block startup.

---

## Core

### `KAUTH_BASE_URL`
**Required.**

The public base URL of the Kotauth instance. Used as the OIDC issuer (`iss` claim), in OIDC discovery documents, OAuth2 redirect URI validation, and email links.

```
KAUTH_BASE_URL=https://auth.yourdomain.com
```

Rules:
- Must start with `https://` when `KAUTH_ENV=production`. The server refuses to start otherwise.
- HTTP is allowed for `localhost` in development mode.
- No trailing slash.

---

### `KAUTH_ENV`
**Optional.** Default: `development`

Controls startup validation strictness.

| Value | Behavior |
|---|---|
| `development` | HTTP allowed, default secrets tolerated, startup warnings printed, welcome page shows live health details |
| `production` | HTTPS required, default JWT secret rejected, strict cookie flags enforced, welcome page hides health details |

```
KAUTH_ENV=production
```

---

### `KAUTH_SECRET_KEY`
**Recommended.**

A 32+ character hex string used for:
- AES-256-GCM encryption of SMTP passwords stored in the database
- HMAC-SHA256 signing of short-lived cookies (MFA pending, PKCE verifier, portal session)

```
KAUTH_SECRET_KEY=<output of: openssl rand -hex 32>
```

Generate one:
```bash
openssl rand -hex 32
```

If not set:
- SMTP configuration cannot be saved (passwords cannot be encrypted)
- Session and cookie signatures use a random key generated at startup — sessions do not survive a container restart
- A warning is printed at startup; the server still starts

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

PostgreSQL password.

```
DB_PASSWORD=changeme
```

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

# Required when using docker/docker-compose.prod.yml (Caddy TLS)
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

See [docs/guides/production-deployment.md](guides/production-deployment.md) for the full deployment walkthrough.
