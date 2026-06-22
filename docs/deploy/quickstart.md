# Quickstart

Run Kotauth locally in one command. Bundled PostgreSQL, demo data pre-loaded, no `.env` file required.

You need Docker and Docker Compose. Nothing else.

```bash
curl -O https://raw.githubusercontent.com/inumansoul/kotauth/main/docker-compose.yml
docker compose up -d
```

Open **http://localhost:8080/admin** and sign in with the credentials shown in the demo banner.

## What you get

- Kotauth on port `8080` (HTTP)
- PostgreSQL 15 (named volume `kotauth_db_data`)
- Demo mode on — two seeded workspaces (Acme Corp, Startup Labs) with users, roles, applications, and audit log entries
- `KAUTH_UPDATE_CHECK=false` so the demo banner doesn't network out

The defaults are unsafe (well-known secret key, hardcoded password). They're fine for local evaluation. When you're ready to run a real instance, see [`production.md`](production.md).

## Demo credentials

| Login | Username | Password |
|---|---|---|
| `/admin` | `admin` | `Demo1234!` |
| `/t/acme/login` | `sarah.chen` | `Demo1234!` |
| `/t/startup-labs/login` | `jordan.lee` | `Demo1234!` |

## Customizing the defaults

Every value in `docker-compose.yml` uses the `${VAR:-default}` pattern. To override, either:

- Drop a `.env` file in the same directory as `docker-compose.yml`:

  ```env
  KAUTH_BASE_URL=http://my-host:8080
  KAUTH_SECRET_KEY=<openssl rand -hex 32>
  KAUTH_DEMO_MODE=false
  DB_PASSWORD=<your password>
  ```

- Or export the var in your shell before `docker compose up`.

The full list of supported variables is in [`../ENV_REFERENCE.md`](../ENV_REFERENCE.md).

## Enabling Redis (optional)

Redis is opt-in. Activate the `redis` profile:

```bash
docker compose --profile redis up -d
```

The Redis sidecar moves rate-limit state and short-lived auth cookies off Postgres. Useful when running more than one Kotauth replica or sustaining high login traffic. Not needed for a single-instance evaluation.

## Stopping

```bash
docker compose down          # stop containers, keep data
docker compose down -v       # stop containers, wipe the database volume
```

## Where next

- [Production deployment](production.md) — TLS, external database, backups, upgrades, security checklist
- [Environment variable reference](../ENV_REFERENCE.md) — every variable Kotauth reads at startup
