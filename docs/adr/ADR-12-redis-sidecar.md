# ADR-12: Redis sidecar for distributed rate limiting and sessions

**Status:** Accepted (v1.8.0)
**Date:** 2026-04-29
**Supersedes:** —
**Related:** ADR-01 (hexagonal architecture), `docs/RATE_LIMITING.md`

## Context

Kotauth's default rate limiter is `InMemoryRateLimiter` — a per-process
sliding window backed by a `ConcurrentHashMap`. It is fast, has zero
operational overhead, and is correct for a single-instance deployment.

Three forces have made this insufficient:

1. **Horizontal scale.** Operators running multiple Kotauth replicas
   behind a load balancer effectively multiply the configured limits by
   the replica count. A 5-attempt login limit with three replicas is a
   15-attempt login limit in practice — the brute-force protection is
   silently weakened by the deployment topology.
2. **Sessions are likewise per-process today.** They live in PostgreSQL
   so they survive a restart, but every login round-trip pays a DB write
   and every authenticated request reads from the same Postgres pool
   that is also serving the OAuth code and audit log writes. As fleets
   grow, this contention shows up.
3. **The horizontal-scale story has to be a one-line config change** —
   not a fork, not a separate distribution, not a code path that only
   works in some configurations. The single-instance default has to keep
   working with no Redis at all.

We needed to decide: which Redis client, which limiter algorithm,
where to draw the configured/unconfigured line, and whether to take on
sessions in the same release.

## Decision

### Lettuce, sync API, single shared connection

We use [Lettuce](https://lettuce.io) as the Redis client. Among the JVM
clients (Jedis, Redisson, Lettuce):

- **Jedis** is connection-per-thread and would force us to introduce a
  pool, multiplying the connection count and the configuration surface.
- **Redisson** ships its own object model, locks, and codecs — far more
  surface than we need, and pulls in a heavier transitive dependency
  graph.
- **Lettuce** is connection-multiplexed — one TCP connection serves
  arbitrarily many concurrent requests because Redis pipelines responses
  in order. The `RedisCommands` sync wrapper hides the netty thread but
  still benefits from the multiplexing underneath.

We instantiate one `StatefulRedisConnection` at startup and use the sync
API throughout the rate limiter and session adapters. The trade-off is
that a slow command can block a request thread; we mitigate this with
an explicit `commandTimeoutMs` (default 100ms) on the connection.
Routes that depend on Redis already have other I/O on the same request,
so an extra 100ms ceiling is not the dominant cost.

### Sliding-window stays — implemented as a Lua script

The existing `InMemoryRateLimiter` uses sliding-window semantics
(timestamp deque, evict-then-count). We keep the same algorithm in
Redis to preserve identical semantics across the configured /
unconfigured branch, so a deployment that turns Redis off for an
incident does not also change rate-limit behavior.

The implementation is a single Lua script (`sliding_window.lua`) that
runs server-side via `EVALSHA`:

```lua
ZREMRANGEBYSCORE key 0 (now - window)   -- expire stale entries
local count = ZCARD key
if count >= max then return { 0, count, 0 } end
ZADD key now <unique-member>
PEXPIRE key (window + 1000)
return { 1, count + 1, max - count - 1 }
```

The script is loaded once per JVM via `SCRIPT LOAD`, the SHA cached, and
re-loaded transparently on `NOSCRIPT` (so a Redis cluster failover or a
manual `SCRIPT FLUSH` does not surface as a 5xx).

Why Lua instead of pipelined commands:

- Atomicity. The check-then-add must be a single round-trip or two
  concurrent requests racing past the limit by 1 is a real outcome.
- Latency. Two-pipeline implementations cost a network round-trip for
  the check and another for the add; Lua collapses to one.

### Fail-closed on the auth path — non-negotiable

When Redis is configured but a command throws `RedisException` (network
partition, downed instance, command timeout), `RedisRateLimiter.isAllowed`
returns `false` — the request is **rejected**. We do not silently
fall back to a per-replica limiter, do not allow-by-default, do not
degrade.

This is deliberate and was reaffirmed during the design. The rate
limiter's whole job is to make brute-force expensive; a "fail open"
mode is an attacker's dream because it triggers exactly when the
operator is least able to investigate. A fail-closed limiter on a
downed Redis surfaces as broad 429s in dashboards — loud, obvious,
fixable. A fail-open limiter on a downed Redis surfaces as a successful
brute-force three days later in an incident postmortem.

The startup probe (see below) ensures that an unreachable Redis is
caught before the server accepts a single request, so steady-state
operators rarely hit the runtime fail-closed branch. When they do, it
has been made loud on purpose.

### Startup probe is a fatal gate, not a warning

When `KAUTH_REDIS_URL` is set, `Application.kt` runs
`RedisHealthProbe.probe(connection, redisStartupProbeTimeoutMs)` after
`ServiceGraph.create()`. The probe sends a `PING` with an explicit
2-second timeout. On failure, the process prints a `FATAL` banner and
calls `exitProcess(1)`.

The alternative — log a warning and continue with the in-memory limiter
— violates the fail-closed contract from the previous section: the
brute-force-protection guarantee that made the operator turn Redis on
in the first place would silently disappear at exactly the moment the
ops team is busiest.

### Configuration mirrors the database split

Following the existing `DB_URL` / `DB_USER` / `DB_PASSWORD` pattern, we
expose three env vars rather than packing credentials into the URL:

```
KAUTH_REDIS_URL=redis://redis-host:6379
KAUTH_REDIS_USERNAME=kotauth        # optional, Redis 6+ ACL
KAUTH_REDIS_PASSWORD=<secret>       # optional
```

This keeps the URL safe to log, lets credentials live in secrets
management without URL-encoding, and matches what operators already
expect from Postgres configuration. Three timing knobs are exposed for
tuning:

- `KAUTH_REDIS_TIMEOUT_MS` (default 250ms) — overall connection timeout.
- `KAUTH_REDIS_COMMAND_TIMEOUT_MS` (default 100ms) — per-command ceiling.
- `KAUTH_REDIS_STARTUP_PROBE_TIMEOUT_MS` (default 2000ms) — startup PING.

The shorter command timeout is intentional: a request thread is held
for the duration of the limiter check, and the call site is on the
auth hot path. 100ms is the operator-facing budget that distinguishes
a healthy Redis from a sick one.

### Hexagonal layering: nothing in domain changes

The existing `RateLimiterPort` interface is unchanged. `RedisRateLimiter`
is a new infrastructure adapter that implements it. `ServiceGraph`
branches on `config.redisEnabled` and constructs the four limiter
buckets (login, register, token, mfa) from either `RedisRateLimiter`
or `InMemoryRateLimiter` — and that is the only consumer-visible
divergence in the wiring.

Routes never know which limiter they're calling. Tests can swap in
`FakeRateLimiter` without spinning up Redis, and `make test` stays
Docker-free; integration tests that exercise the real Lua + redis-cli
paths are tagged `@Tag("redis")` and run via `make test-redis`
(Testcontainers).

### Sessions move to Redis when configured (Phase 2)

Sessions today live in PostgreSQL. Phase 2 of v1.8.0 introduces a
`RedisSessionRepository` that becomes the storage when
`KAUTH_REDIS_URL` is set; without Redis, the existing
`PostgresSessionRepository` keeps its current behavior.

Sessions are inherently ephemeral and high-cardinality — well-suited to
Redis. We use an explicit per-key TTL aligned with the session's
absolute expiry, so cleanup happens for free and there is no equivalent
to the Postgres "expired sessions" sweeper.

Session move is in a separate phase because:

- Phase 1 (rate limiter) is the immediate horizontal-scale fix.
  Operators can ship Phase 1 alone and have a correct multi-replica
  story already.
- Phase 2 alters a write-path that exists on every login round-trip;
  it deserves its own observation window and rollback path.

## Consequences

### Positive

- **Multi-replica deployments are correct by default** when Redis is
  configured: limits apply across the fleet, not per replica.
- **Single-instance deployments stay zero-overhead.** No Redis required,
  no flag to flip — the absence of `KAUTH_REDIS_URL` is the off switch.
- **Fail-closed is the only mode.** Operators don't have to remember to
  turn it on, and there's no "best effort" path that silently weakens
  protections under partial failure.
- **Sliding-window semantics are identical** across configured and
  unconfigured. Switching topologies for an incident does not change
  what counts as rate-limited.
- **The blast radius of a bad Redis is bounded.** Rate-limited requests
  return 429; sessions become unavailable (so users re-auth). Auth
  itself doesn't lock up — the JWTs already in flight remain valid.

### Negative — known limitations

1. **Sync API blocks request threads on slow Redis.** A `RedisException`
   timeout holds a Netty / coroutine thread for up to
   `KAUTH_REDIS_COMMAND_TIMEOUT_MS`. We picked 100ms as the operator
   budget. Async API would relieve this but at the cost of bringing
   `CompletionStage` into call sites that are otherwise sync.
2. **Lua script is per-keyspace.** A Redis cluster setup needs all four
   bucket keys to hash to the same slot, or the script can't `EVAL`
   across them. Today we use a single Redis instance; cluster support is
   an explicit non-goal for v1.8.0.
3. **No connection-level circuit breaker.** Lettuce's
   `REJECT_COMMANDS` disconnect behavior plus `autoReconnect(true)` is
   the only resilience layer; if Redis is flapping, we'll reject the
   in-flight commands and try again on the next request. We have not
   added a "fast-fail for N seconds after a failure" breaker because
   the request-level command timeout (100ms) already bounds the impact.
4. **Caches stay per-process.** HIBP cache, CORS cache, claim mapper
   cache continue to live in JVM memory. They are read-mostly and
   eventually consistent across replicas; moving them to Redis would
   trade per-process miss latency for network latency on every read.
   Revisit only if a specific cache becomes a hotspot.

### Neutral

- Operators running multiple instances behind a load balancer must
  also pin sticky sessions or move sessions to Redis (Phase 2). Until
  Phase 2 ships, multi-replica with Postgres sessions works but every
  login flow incurs at least one cross-replica session lookup.
- Redis 6+ ACL with username/password is supported via the env-var
  split; legacy Redis 5 password-only is supported by passing only
  `KAUTH_REDIS_PASSWORD` (Lettuce wraps this as a
  `default`-user ACL credential).

## Alternatives considered

- **Redisson with its `RRateLimiter`.** Comes with a built-in fixed-rate
  limiter primitive. Rejected because it forces a fixed-rate algorithm
  that is not the sliding window we use elsewhere and pulls in a heavy
  dependency graph (codec providers, distributed object types).
- **Token-bucket via `INCR` + `EXPIRE`.** Simpler than the sliding
  window — a single `INCR` per request and a TTL — but loses the
  fairness property of the sliding window: a 5-per-minute limit
  becomes "5 per discrete minute" instead of "5 in any 60s rolling".
  The product behavior would be observably worse at the wraparound.
- **Memcached.** No `EVAL`, no atomic compound operations. Would require
  Lua-equivalent logic to live in client code with optimistic-lock
  retries. Rejected.
- **Per-replica limiter + best-effort coordination.** A peer-to-peer
  gossip of bucket counts. Strictly weaker than Redis (eventually
  consistent, partition-fragile) and operationally heavier (peer
  discovery, jepsen-class failure modes). Rejected.
- **Logging a warning and falling back to per-replica when Redis is
  down.** Explicitly rejected — see "Fail-closed on the auth path"
  above.
- **Async Lettuce API throughout.** Would scale better under sustained
  Redis pressure. Rejected for v1.8.0 because it changes the call-site
  signatures for every limiter consumer; the sync API matches the rest
  of the adapter layer and the cost ceiling (100ms timeout) is small.
