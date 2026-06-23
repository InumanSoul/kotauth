# Production deployment

This guide takes you from a fresh Linux server to a running Kotauth instance behind HTTPS, with backups, secrets management, and an upgrade path.

## Prerequisites

- Docker and Docker Compose on a Linux host
- A domain with an A record pointing to the server (e.g. `auth.yourdomain.com`)
- Ports `80` and `443` open on the host firewall
- Port `5432` blocked from the public internet

## 1. Pull the files

You don't need to clone the repo. Fetch the production compose file, the Caddy config, and the env template:

```bash
mkdir kotauth && cd kotauth

curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.prod.yml
curl --create-dirs -o docker/Caddyfile \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/Caddyfile
curl -o .env https://raw.githubusercontent.com/inumansoul/kotauth/main/.env.example
```

## 2. Configure `.env`

Open `.env` and fill in every value. Nothing should be left blank in production.

```env
KAUTH_BASE_URL=https://auth.yourdomain.com
KAUTH_ENV=production
KAUTH_SECRET_KEY=<openssl rand -hex 32>

DB_NAME=kotauth_db
DB_USER=kotauth
DB_PASSWORD=<strong random password>

DOMAIN=auth.yourdomain.com
ACME_EMAIL=you@yourdomain.com
```

`KAUTH_SECRET_KEY` is the most important value — it encrypts every secret at rest (SMTP credentials, TOTP enrolments, RSA private keys) and signs session cookies. Generate it with `openssl rand -hex 32` or `docker run --rm ghcr.io/inumansoul/kotauth:latest cli generate-secret-key`. Lose it and you lose every encrypted value in the database.

## 3. Start

```bash
docker compose -f docker-compose.prod.yml up -d
```

Three services come up: `app` (Kotauth), `db` (PostgreSQL 15), and `caddy` (Let's Encrypt TLS). Caddy obtains a certificate on first boot via the ACME HTTP-01 challenge, which is why port `80` has to be reachable.

## 4. Verify

```bash
curl -s https://auth.yourdomain.com/health/ready
curl -s https://auth.yourdomain.com/.well-known/openid-configuration | jq .issuer
```

Then open `https://auth.yourdomain.com/admin` and change the master workspace admin password immediately. The initial password is printed to the container logs on first boot:

```bash
docker compose -f docker-compose.prod.yml logs app | grep "Admin credentials"
```

## 5. Optional: enable Redis

Redis is opt-in via the `redis` profile. Add it when:

- you run more than one Kotauth replica (Redis-backed rate-limit state is shared across replicas)
- single-instance auth traffic is high enough to dominate the database

```bash
docker compose -f docker-compose.prod.yml --profile redis up -d
```

In `.env`:

```env
KAUTH_REDIS_URL=redis://redis:6379
```

Kotauth's Redis path fails closed — if Redis becomes unreachable, auth requests are rejected rather than falling back to per-replica state.

## 6. Use a managed database

To use RDS, Supabase, Neon, or any existing PostgreSQL instance instead of the bundled `db` service, set `DB_URL` in `.env`:

```env
DB_URL=jdbc:postgresql://your-host:5432/kotauth_db?sslmode=require
DB_USER=kotauth
DB_PASSWORD=<the managed-db password>
```

The bundled `db` service still starts but receives no traffic — Flyway runs against the external database. If you want to remove the idle container entirely, comment out the `db` service block and the `depends_on: db` line in `docker-compose.prod.yml`.

Flyway runs all migrations automatically. Point it at an empty database.

## 7. File-based secrets

For Docker Swarm, Kubernetes mounted secrets, or systemd `LoadCredential=`, every sensitive variable accepts a `<NAME>_FILE` form. The file's contents are read and trimmed at startup. `<NAME>_FILE` wins over `<NAME>` when both are set.

Supported variables:

- `KAUTH_SECRET_KEY_FILE`
- `DB_PASSWORD_FILE`
- `KAUTH_REDIS_PASSWORD_FILE`
- `KAUTH_BOOTSTRAP_ADMIN_PASSWORD_FILE`
- `KAUTH_BOOTSTRAP_API_KEYS_FILE`

Example using Docker Swarm secrets:

```bash
printf %s "$(openssl rand -hex 32)" | docker secret create kauth_secret_key -
printf %s "your-db-password"        | docker secret create db_password       -
```

```yaml
secrets:
  kauth_secret_key:
    external: true
  db_password:
    external: true

services:
  app:
    secrets:
      - kauth_secret_key
      - db_password
    environment:
      KAUTH_SECRET_KEY_FILE: /run/secrets/kauth_secret_key
      DB_PASSWORD_FILE: /run/secrets/db_password
```

Remove the corresponding plaintext env vars from `.env` when using this path.

## 8. Backups

Named volumes:

| Volume | Contents |
|---|---|
| `kotauth_db_data` | PostgreSQL data — every tenant, user, session, audit row |
| `caddy_data` | TLS certificates and ACME state |
| `caddy_config` | Caddy runtime config |

Only `kotauth_db_data` matters for backups. Caddy state is regenerable.

```bash
docker exec kotauth-db pg_dump -U kotauth kotauth_db > backup_$(date +%Y%m%d).sql
```

Restore:

```bash
cat backup_20260101.sql | docker exec -i kotauth-db psql -U kotauth -d kotauth_db
```

## 9. Upgrades

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

Flyway runs new migrations before the server accepts traffic. If a migration fails, the container exits and the previous version remains in place. Take a database backup before crossing a major version.

Pin a specific image tag instead of tracking `latest`:

```yaml
services:
  app:
    image: ghcr.io/inumansoul/kotauth:1.19.2
```

## 10. Reverse proxy alternatives

The bundled `caddy` service handles TLS for a single-domain single-host deployment. If you already run your own reverse proxy (nginx, Traefik, an L7 load balancer), remove the `caddy` service from `docker-compose.prod.yml` and proxy your existing edge to port `8080`.

### nginx

```nginx
server {
    listen 443 ssl;
    server_name auth.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/auth.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/auth.yourdomain.com/privkey.pem;

    location / {
        proxy_pass         http://localhost:8080;
        proxy_set_header   Host $host;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-Proto https;
    }
}
```

Set `KAUTH_TRUSTED_PROXY=true` so Kotauth honors `X-Forwarded-For`. Never set this without a reverse proxy in front — it lets clients spoof the IPs that gate rate limiting.

### Traefik

```yaml
services:
  app:
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.kotauth.rule=Host(`auth.yourdomain.com`)"
      - "traefik.http.routers.kotauth.entrypoints=websecure"
      - "traefik.http.routers.kotauth.tls.certresolver=letsencrypt"
      - "traefik.http.services.kotauth.loadbalancer.server.port=8080"
```

## 11. Demo deployment

Run a public showcase instance (e.g. `demo.yourdomain.com`) with pre-seeded workspaces:

```env
KAUTH_DEMO_MODE=true
```

Restart and the demo seed runs on startup, creating two workspaces with users, applications, roles, and audit log entries. A sticky banner exposes the demo credentials on every page.

Hourly reset (the only sensible cadence for a public demo):

```cron
0 * * * * cd /opt/kotauth && docker compose -f docker-compose.prod.yml down -v && docker compose -f docker-compose.prod.yml up -d
```

`-v` wipes `kotauth_db_data`. Flyway re-migrates from scratch and `DemoSeedService` re-creates the demo data.

## 12. Security checklist

- [ ] `KAUTH_ENV=production` — enforces HTTPS and strict cookie flags
- [ ] `KAUTH_BASE_URL` starts with `https://` — the server refuses to start otherwise
- [ ] `KAUTH_SECRET_KEY` was generated fresh, never reused from another environment
- [ ] `DB_PASSWORD` is a strong random value, not `changeme`
- [ ] Port `5432` blocked at the host firewall
- [ ] Master workspace admin password rotated after first login
- [ ] Backups scheduled for the `kotauth_db_data` volume
- [ ] If using `KAUTH_TRUSTED_PROXY=true`, the proxy in front overwrites client-supplied `X-Forwarded-*` headers

## CLI tools

The same JAR ships CLI subcommands. Run them via the existing container:

```bash
docker compose exec app java -jar /app/kauth.jar cli generate-secret-key
docker compose exec app java -jar /app/kauth.jar cli reset-admin-mfa --username=admin
docker compose exec app java -jar /app/kauth.jar cli --help
```
