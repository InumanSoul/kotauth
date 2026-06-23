# Production deployment

## Prerequisites

- Docker and Docker Compose on a Linux host
- A domain with an A record pointing to the server (e.g. `auth.yourdomain.com`)
- Ports `80` and `443` open on the host firewall
- Port `5432` blocked from the public internet

## 1. Pull the files

No repo clone required:

```bash
mkdir kotauth && cd kotauth

curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.prod.yml
curl --create-dirs -o docker/Caddyfile \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/Caddyfile
curl -o .env https://raw.githubusercontent.com/inumansoul/kotauth/main/.env.example
```

## 2. Configure `.env`

Fill every value:

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

`KAUTH_SECRET_KEY` encrypts every secret at rest (SMTP credentials, TOTP enrolments, RSA private keys) and signs session cookies. Generate it with `openssl rand -hex 32`. Never reuse it across environments.

## 3. Start

```bash
docker compose -f docker-compose.prod.yml up -d
```

Three services: `app`, `db` (PostgreSQL 15), `caddy` (Let's Encrypt TLS via ACME HTTP-01).

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

The Redis path fails closed: if Redis becomes unreachable, auth requests are rejected rather than falling back to per-replica state.

## 6. Use a managed database

To use RDS, Supabase, Neon, or any existing PostgreSQL instance instead of the bundled `db` service, set `DB_URL` in `.env`:

```env
DB_URL=jdbc:postgresql://your-host:5432/kotauth_db?sslmode=require
DB_USER=kotauth
DB_PASSWORD=<the managed-db password>
```

Flyway runs against the external database. The bundled `db` service still starts but receives no traffic — to remove the idle container, comment out the `db` service block and its `depends_on: db` line in `docker-compose.prod.yml`.

## 7. File-based secrets

For Docker Swarm, Kubernetes mounted secrets, or systemd `LoadCredential=`, every sensitive variable accepts a `<NAME>_FILE` form (contents read and trimmed at startup; `<NAME>_FILE` takes precedence over `<NAME>`).

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
| `caddy_data` | TLS certificates and ACME state (regenerable) |
| `caddy_config` | Caddy runtime config (regenerable) |

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

Flyway auto-migrates before accepting traffic; a failed migration halts the container and leaves the previous version running. Take a backup before major version bumps.

Pin a specific image tag instead of tracking `latest`:

```yaml
services:
  app:
    image: ghcr.io/inumansoul/kotauth:1.19.2
```

## 10. Reverse proxy alternatives

If you already run your own reverse proxy (nginx, Traefik, an L7 load balancer), remove the `caddy` service from `docker-compose.prod.yml` and proxy your existing edge to port `8080`.

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

For a public showcase (e.g. `demo.yourdomain.com`), set `KAUTH_DEMO_MODE=true` in `.env`. The seed and credentials are documented in [quickstart](quickstart.md#demo-credentials).

For periodic reset (hourly is typical for public demos):

```cron
0 * * * * cd /opt/kotauth && docker compose -f docker-compose.prod.yml down -v && docker compose -f docker-compose.prod.yml up -d
```

`-v` wipes `kotauth_db_data`; Flyway re-migrates and the demo seed runs again.

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
