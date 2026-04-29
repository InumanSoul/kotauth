# Redis

Optional sidecar that backs **distributed rate limiting** in Kotauth.
Without Redis, the server uses a per-process in-memory limiter — fine
for a single instance, weakened by the replica count if you scale
horizontally.

If you run **one Kotauth replica**, you can stop reading here.
If you run **two or more replicas behind a load balancer**, Redis is
how you keep your rate limits honest.

> Sessions also move to Redis when configured — see
> [Session storage](#session-storage). Falls back to PostgreSQL when
> `KAUTH_REDIS_URL` is unset.

---

## Configuration

| Env var | Required | Default | Notes |
|---|---|---|---|
| `KAUTH_REDIS_URL` | No | _unset_ | `redis://host:port[/db]` or `rediss://...` for TLS. Setting this turns Redis on. |
| `KAUTH_REDIS_USERNAME` | No | _unset_ | Redis 6+ ACL username. Omit for password-only auth. |
| `KAUTH_REDIS_PASSWORD` | No | _unset_ | Redis password. Sent as `default`-user credential when `USERNAME` is unset. |
| `KAUTH_REDIS_TIMEOUT_MS` | No | `250` | Connection-level timeout. |
| `KAUTH_REDIS_COMMAND_TIMEOUT_MS` | No | `100` | Per-command ceiling on the auth hot path. Don't raise this without measuring. |
| `KAUTH_REDIS_STARTUP_PROBE_TIMEOUT_MS` | No | `2000` | PING timeout at startup. Higher in slow networks; lower if you want the gate to fail faster. |

Credentials live in their own variables instead of inside the URL,
mirroring the `DB_URL` / `DB_USER` / `DB_PASSWORD` split. The URL is
safe to log; the credentials live in your secrets manager.

### Minimum local example

```bash
KAUTH_REDIS_URL=redis://localhost:6379
```

### Production with ACL

```bash
KAUTH_REDIS_URL=rediss://redis.internal:6380
KAUTH_REDIS_USERNAME=kotauth
KAUTH_REDIS_PASSWORD=<secret>
```

---

## What Redis is used for

| Concern | Without `KAUTH_REDIS_URL` | With `KAUTH_REDIS_URL` |
|---|---|---|
| Rate limiting (login, MFA, register, token) | `InMemoryRateLimiter` per process | Sliding-window Lua script in Redis (atomic) |
| Sessions | `PostgresSessionRepository` | `RedisSessionRepository` (TTL-driven cleanup) |
| Caches (HIBP, CORS, claim mapper) | Per-process JVM memory | Per-process JVM memory (intentionally) |
| OAuth2 codes, audit log, tenants, users, etc. | PostgreSQL | PostgreSQL |

Redis is a coordinator for **what has to be coherent across replicas**.
Anything that is read-mostly and survives a few seconds of staleness
stays in process memory.

---

## Fail-closed contract

Two scenarios, two behaviors, both intentional:

1. **`KAUTH_REDIS_URL` is set, Redis is unreachable at startup.**
   Kotauth refuses to start. The PING probe runs after `ServiceGraph.create`
   and prints a `FATAL` banner before `exitProcess(1)`. Operators see the
   misconfiguration loudly, immediately, and before any user request lands.

2. **`KAUTH_REDIS_URL` is set, Redis becomes unreachable later.**
   Rate-limited routes return `false` from `isAllowed` — the request is
   **rejected** with the normal "rate-limited" UX (login page error,
   429 on the token endpoint, etc.). The server does not silently fall
   back to a per-replica limiter. Users see 429s; dashboards light up;
   you find out within seconds.

The runtime fail-closed is the load-bearing decision. The whole point
of having a rate limiter is to make brute-force expensive — a "fail
open" mode is a brute-forcer's dream because it triggers exactly when
the operator is least able to investigate. Loud over silent, every
time.

If Redis is genuinely down for an extended window, your options are
(a) restore Redis, (b) restart the server with `KAUTH_REDIS_URL` unset
to fall back to the in-memory limiter for the duration. Option (b)
weakens the multi-replica guarantee but keeps the auth flow open; pick
deliberately.

---

## Deployment topology

### Bundled compose (development)

The repo's `docker/docker-compose.dev.yml` ships a `redis:7-alpine`
service:

```yaml
redis:
  image: redis:7-alpine
  command: ["redis-server", "--appendonly", "no", "--save", ""]
  ports:
    - "6379:6379"
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
```

Persistence is disabled deliberately — rate-limit buckets and sessions
are ephemeral by design, so AOF/RDB only adds I/O for no recovery
benefit. If you reuse this compose for staging or production, **do not
turn persistence on** thinking it's safer; it isn't, and a Redis
instance silently writing to a slow disk will start blocking the auth
path.

### Production

Run a single Redis instance close to your Kotauth replicas (same
private network, same AZ if possible). Sentinel or managed-Redis
(ElastiCache, Upstash, Memorystore) is fine — Lettuce talks to them
without configuration changes as long as the URL points at the
endpoint.

**Cluster mode is an explicit non-goal for v1.8.0.** The Lua script
expects all four limiter buckets to live in the same keyspace; a Redis
cluster splits keys across slots and the `EVAL` will fail. If you
already run a Redis cluster, point Kotauth at one of its primary nodes
or run a separate non-cluster instance for it.

### Resource sizing

The rate-limiter buckets are tiny:

- ~80 bytes per active key (the ZSET member + score)
- TTL is `windowSeconds + 1s` so dead keys evict themselves

A fleet handling 10k req/min hits ~10k active keys at most (one per
{IP, tenant} pair). That's under 1 MB in Redis. Sessions are larger
(~500 bytes per active session) but also TTL-bounded.

A `redis:7-alpine` container with default 100 MB of memory is enough
for any deployment Kotauth is realistically running today.

---

## Session storage

When `KAUTH_REDIS_URL` is set, sessions live in Redis with per-record
TTL aligned to `max(expiresAt, refreshExpiresAt)` plus a 7-day retention
buffer. Redis evicts records automatically — there is no sweeper task.

When `KAUTH_REDIS_URL` is **not** set, sessions live in PostgreSQL via
`PostgresSessionRepository` (the default since v1.0). The hourly
`deleteExpired` sweeper continues to run.

You do not flip a separate flag to switch storage; setting
`KAUTH_REDIS_URL` flips it. Migrating an existing fleet from
Postgres-sessions to Redis-sessions effectively logs everyone out
(Redis starts empty); plan accordingly during a rollout.

### Keyspace layout

```
kauth:session:rec:{id}                       STRING (JSON)  primary record
kauth:session:tok:{accessTokenHash}          STRING         access-token → id
kauth:session:rtok:{refreshTokenHash}        STRING         refresh-token → id
kauth:session:active:user:{tenant}:{user}    ZSET           (createdAtEpochMs, id)
kauth:session:active:tenant:{tenant}         ZSET           (createdAtEpochMs, id)
kauth:session:next-id                        STRING         INCR counter
```

The active sets are kept in sync on save / revoke and **opportunistically
reconciled on read** — when a record has been TTL'd out but the set
member remains, the read path drops it via `ZREM`. This means counts
returned to the application are exact; the on-disk shape may briefly
contain orphans before the next read.

### Concurrent-session enforcement

`countActiveByUser` and `revokeOldestForUser` use the per-user ZSET to
enforce `Tenant.maxConcurrentSessions`. The ZSET is scored by
`createdAt` epoch-millis so "oldest first" is a single `ZRANGE`.

### Pagination

`findActiveByTenant(limit, offset)` reads the full per-tenant set,
filters live, sorts newest-first, then paginates. For tenants with
many active sessions this is O(N) per call and warrants a follow-up if
it becomes a hotspot in practice. For typical workspaces (≤ a few
hundred concurrent sessions) it is well below the per-command timeout.

---

## Operations

### Health probe

Kotauth runs `RedisHealthProbe.probe(connection, timeoutMs)` at startup.
It sends `PING` and expects `PONG`. The probe has its own timeout
(`KAUTH_REDIS_STARTUP_PROBE_TIMEOUT_MS`, default 2 s) separate from the
per-command timeout, because a hanging connection at startup should not
take a fraction of a per-request budget to discover.

Failure prints:

```
┌──────────────────────────────────────────────────────────────┐
│  FATAL: KAUTH_REDIS_URL is set but Redis is unreachable.    │
│                                                              │
│  Refusing to start: rate limiting must be backed by Redis    │
│  when configured. Falling back to per-replica limiters       │
│  would silently weaken auth-flow protections.                │
│                                                              │
│  Verify the URL, credentials, and network reachability.      │
└──────────────────────────────────────────────────────────────┘
```

…and exits the process. Your orchestrator (k8s, ECS, systemd) sees the
non-zero exit and applies its restart policy. We deliberately don't
retry inside Kotauth — that's the orchestrator's job, and conflating
the two leaves the operator without a clear "is the gate failing?"
signal.

### Inspecting buckets

The keyspace looks like:

```
kauth:rl:<bucket>:<key>     (rate-limiter ZSET)
```

Where `<bucket>` is one of `login`, `register`, `token`, `mfa`, and
`<key>` is the limiter input (typically `<ip>:<tenant-slug>`). To
inspect:

```bash
redis-cli KEYS 'kauth:rl:login:*'
redis-cli ZRANGE 'kauth:rl:login:1.2.3.4:acme' 0 -1 WITHSCORES
redis-cli DEL 'kauth:rl:login:1.2.3.4:acme'   # manual unlock
```

The Kotauth limiter exposes the same operation programmatically via
`RateLimiterPort.reset(key)`, which is what the admin "unlock account"
flow calls.

### Shutdown

The shared connection is closed in the JVM shutdown hook alongside the
HTTP server and the application coroutine scope. If you `kill -9` the
Kotauth process, Redis sees a TCP FIN on its own; nothing leaks.

---

## Troubleshooting

**`FATAL: KAUTH_REDIS_URL has an unsupported scheme.`**
The URL must start with `redis://` or `rediss://`. Anything else
(`http://`, `tcp://`, raw hostname) is rejected by `EnvironmentConfig`.

**`Redis startup probe failed: Redis PING timed out after 2000ms`**
The TCP socket opened (otherwise you'd get a different exception) but
the response didn't come back in time. Almost always a network-level
issue — verify the URL hostname resolves, the port is open, and your
Redis is actually listening (`redis-cli -h <host> -p <port> ping`).

**`Redis PING failed: WRONGPASS invalid username-password pair`**
Mismatched credentials. Your `KAUTH_REDIS_PASSWORD` and your Redis ACL
disagree. If you're not using ACLs, check for stray `KAUTH_REDIS_USERNAME`
that's getting interpreted as an ACL user.

**Steady-state 429s on the auth path with no traffic spike.**
Almost always a Redis outage — check connection metrics. The fail-closed
contract is doing its job. Restore Redis or restart with
`KAUTH_REDIS_URL` unset (see "Fail-closed contract" above).

**Limits feel ~Nx looser than configured (where N is replica count).**
`KAUTH_REDIS_URL` is unset. The in-memory limiter is per-process; each
replica enforces the configured limit independently. Set
`KAUTH_REDIS_URL` and roll the fleet.

---

## Testing

`make test` runs the full suite without Docker. Redis-backed integration
tests are tagged `@Tag("redis")` and excluded from the default run.

To execute them:

```bash
make test-redis
```

This spins up `redis:7-alpine` via Testcontainers, exercises the real
Lua script and command paths, and tears the container down on exit.
Docker is required.

If your local Docker context isn't Docker Desktop (e.g., OrbStack or
Colima), the `make test-redis` target auto-detects via
`docker context inspect` and forwards `DOCKER_HOST` plus an explicit
`api.version` system property — Testcontainers doesn't always negotiate
correctly with non-Docker-Desktop runtimes, so we set this to be safe.

---

## Related

- [Rate limiting](RATE_LIMITING.md) — endpoint-level limits and
  algorithm details.
- [ADR-12](adr/ADR-12-redis-sidecar.md) — design decisions and
  alternatives considered.
- [ENV_REFERENCE](ENV_REFERENCE.md) — full env-var index.
