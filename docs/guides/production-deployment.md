# Production Deployment

This guide covers deploying Kotauth to a production server with TLS, persistent storage, and a secure configuration.

---

## Prerequisites

- A Linux server with Docker and Docker Compose installed
- A domain name with an A record pointing to the server's IP (e.g. `auth.yourdomain.com`)
- Ports **80** and **443** open on the host firewall
- Port **5432** closed to the public internet (database should never be exposed)

---

## 1. Get the compose files

No repo clone required. Download the two files you need:

```bash
mkdir kotauth && cd kotauth

curl --create-dirs -o docker/docker-compose.yml \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/docker-compose.yml
curl --create-dirs -o docker/docker-compose.prod.yml \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/docker-compose.prod.yml
curl --create-dirs -o docker/Caddyfile \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/Caddyfile
curl -o .env.example \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/.env.example
cp .env.example .env
```

---

## 2. Configure `.env`

Open `.env` and fill in every value. Nothing should be left blank in production.

```env
# ─── Kotauth ──────────────────────────────────────────────────────────────────
KAUTH_BASE_URL=https://auth.yourdomain.com
KAUTH_ENV=production
KAUTH_SECRET_KEY=<run: openssl rand -hex 32>

# ─── Database ─────────────────────────────────────────────────────────────────
DB_NAME=kotauth_db
DB_USER=kotauth
DB_PASSWORD=<strong random password>

# ─── Caddy (used by docker-compose.prod.yml) ──────────────────────────────────
DOMAIN=auth.yourdomain.com
ACME_EMAIL=you@yourdomain.com
```

`KAUTH_SECRET_KEY` is the most important value. Generate it properly:

```bash
openssl rand -hex 32
```

Never reuse a key across environments. If this key is lost or rotated, all existing SMTP configurations will need to be re-entered (they are encrypted with this key), and all active sessions will be invalidated.

---

## 3. Start the production stack

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod.yml up -d
```

This starts three services:
- **db** — PostgreSQL 15 with a persistent named volume
- **app** — Kotauth (pre-built from GHCR), waits for the database to be healthy
- **caddy** — Automatic TLS via Let's Encrypt, reverse proxies to the app on port 8080

Caddy will obtain a TLS certificate on first startup. This requires port 80 to be reachable for the ACME HTTP-01 challenge. The certificate is stored in the `caddy_data` volume and renewed automatically.

---

## 4. Verify the deployment

Check that the health endpoint returns 200:

```bash
curl -s https://auth.yourdomain.com/health/ready
```

Check that the OIDC discovery document is accessible:

```bash
curl -s https://auth.yourdomain.com/.well-known/openid-configuration | jq .issuer
# Should return: "https://auth.yourdomain.com"
```

Open the admin console and change the default master workspace credentials immediately:

```
https://auth.yourdomain.com/admin
```

---

## 5. Persistent data

The `docker-compose.yml` declares two named volumes:

| Volume | Contents |
|---|---|
| `kotauth_db_data` | PostgreSQL data directory — all user data, tenants, sessions |
| `caddy_data` | TLS certificates and Caddy state |
| `caddy_config` | Caddy runtime configuration |

**Back up `kotauth_db_data` regularly.** This is the only stateful volume that matters for Kotauth. Caddy certificates are renewable from scratch if lost.

### PostgreSQL backup

```bash
docker exec kotauth-db pg_dump -U kotauth kotauth_db > backup_$(date +%Y%m%d).sql
```

### Restore

```bash
cat backup_20260101.sql | docker exec -i kotauth-db psql -U kotauth -d kotauth_db
```

---

## 6. Upgrading

Kotauth uses Flyway for schema migrations. Upgrades are handled automatically on startup — pull the new image and restart:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod.yml pull
docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod.yml up -d
```

Flyway runs any new migrations before the server begins accepting traffic. If a migration fails, the container will exit and the previous version remains in place. Always back up your database before upgrading between major versions.

To pin to a specific version instead of tracking `latest`:

```yaml
# In docker-compose.yml, change:
image: ghcr.io/inumansoul/kotauth:latest
# To:
image: ghcr.io/inumansoul/kotauth:1.0.1
```

---

## 7. Reverse proxy alternatives

### nginx

If you prefer nginx over Caddy, handle TLS termination externally (e.g. via Certbot) and proxy to port 8080. Remove the `caddy` service from `docker-compose.prod.yml` and restore port exposure on the app:

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

### Traefik

If Traefik is already running on the host as the cluster edge, label the app container and remove the Caddy service. Example labels for `docker-compose.prod.yml`:

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

---

## 8. Security checklist

- [ ] `KAUTH_ENV=production` is set — enforces HTTPS and strict cookie flags
- [ ] `KAUTH_BASE_URL` starts with `https://` — the server refuses to start otherwise
- [ ] `KAUTH_SECRET_KEY` was generated fresh with `openssl rand -hex 32`
- [ ] `DB_PASSWORD` is a strong, unique password (not `changeme`)
- [ ] Port 5432 is blocked on the host firewall — database is not publicly accessible
- [ ] Default master workspace admin password has been changed
- [ ] Backups are scheduled for the `kotauth_db_data` volume

---

## 9. Demo deployment

For a public showcase instance (e.g. `demo.kotauth.com`) that visitors can explore without signing up:

**1. Add the demo overlay**

Download the additional compose file:

```bash
curl --create-dirs -o docker/docker-compose.demo.yml \
  https://raw.githubusercontent.com/inumansoul/kotauth/main/docker/docker-compose.demo.yml
```

**2. Start with all three compose files**

```bash
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.prod.yml \
  -f docker/docker-compose.demo.yml \
  up -d
```

The demo overlay sets `KAUTH_DEMO_MODE=true`. On startup, Kotauth seeds two workspaces ("Acme Corp" with a dark theme and "Startup Labs" with a light theme), pre-populated with users, applications, roles, groups, webhook endpoints, and audit log entries. A sticky amber banner appears on every page showing demo credentials.

**3. Schedule hourly resets**

Demo instances should reset periodically so visitors always see a clean state. Add a cron job:

```bash
crontab -e
# Add:
0 * * * * cd /opt/kotauth && docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod.yml -f docker/docker-compose.demo.yml down -v && docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod.yml -f docker/docker-compose.demo.yml up -d
```

The `-v` flag destroys the database volume. On restart, Flyway re-migrates from scratch and `DemoSeedService` re-creates all demo data.

**Demo credentials:**

| Login page | Username | Password |
|---|---|---|
| `/admin` (master workspace) | `admin` | `changeme123!` |
| `/t/acme/login` | `sarah.chen` | `Demo1234!` |
| `/t/startup-labs/login` | `jordan.lee` | `Demo1234!` |

---

## Environment variable reference

See [docs/ENV_REFERENCE.md](../ENV_REFERENCE.md) for the complete list of variables, including per-tenant SMTP, MFA policy, and demo mode settings.
