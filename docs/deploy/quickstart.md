# Quickstart

Requires Docker and Docker Compose.

```bash
curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.yml
docker compose up -d
```

Open **http://localhost:8080/admin**, sign in with the credentials shown in the demo banner.

## What you get

- Kotauth on port `8080` (HTTP)
- PostgreSQL 15 (named volume `kotauth_db_data`)
- Demo mode on — two seeded workspaces (Acme Corp, Startup Labs) with users, roles, applications, and audit log entries
- `KAUTH_UPDATE_CHECK=false` so the banner doesn't network out

The defaults are unsafe for anything beyond local evaluation. See [`production.md`](production.md) when you're ready to deploy.

## Demo credentials

| Login | Username | Password |
|---|---|---|
| `/admin` | `admin` | `Demo1234!` |
| `/t/acme/login` | `sarah.chen` | `Demo1234!` |
| `/t/startup-labs/login` | `jordan.lee` | `Demo1234!` |

## Customizing the defaults

Every value in `docker-compose.yml` uses `${VAR:-default}` substitution. Override by dropping a `.env` file next to `docker-compose.yml` or exporting the var in your shell:

```env
KAUTH_BASE_URL=http://my-host:8080
KAUTH_SECRET_KEY=<openssl rand -hex 32>
KAUTH_DEMO_MODE=false
DB_PASSWORD=<your password>
```

Full variable list: [`../ENV_REFERENCE.md`](../ENV_REFERENCE.md).

## Redis (optional)

```bash
docker compose --profile redis up -d
```

Moves rate-limit state and session cookies off Postgres. Enable for multi-replica setups or high login traffic.

## Stopping

```bash
docker compose down          # stop containers, keep data
docker compose down -v       # stop containers, wipe the database volume
```

## Where next

- [Production deployment](production.md) — TLS, external database, backups, upgrades, security checklist
- [Environment variable reference](../ENV_REFERENCE.md) — every variable Kotauth reads at startup
