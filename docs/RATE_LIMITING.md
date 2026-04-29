# Rate Limiting

Kotauth protects authentication endpoints with a sliding-window rate limiter. Each protected endpoint tracks requests per IP address per tenant and rejects with `429 Too Many Requests` when the limit is exceeded.

---

## Protected Endpoints

| Endpoint | Key format | Limit | Window |
|---|---|---|---|
| `POST /t/{slug}/authorize` (login) | `login:{ip}:{slug}` | 5 requests | 60 seconds |
| `POST /t/{slug}/mfa-challenge` | `mfa:{ip}:{slug}` | 5 requests | 5 minutes |
| `POST /t/{slug}/register` | `register:{ip}:{slug}` | 3 requests | 5 minutes |
| `POST /t/{slug}/forgot-password` | `forgot:{ip}:{slug}` | 3 requests | 5 minutes |
| `POST /t/{slug}/reset-password` | `reset:{ip}:{slug}` | 3 requests | 5 minutes |
| `POST /t/{slug}/protocol/openid-connect/token` | `token:{ip}:{slug}` | 20 requests | 60 seconds |

Limits are configured in `ServiceGraph.kt` and apply per-IP per-tenant. A user on IP `1.2.3.4` hitting the `acme` workspace login is tracked independently from the same IP hitting the `demo` workspace.

---

## Architecture

Rate limiting is defined as an outbound port (`RateLimiterPort`) in the domain layer. The route adapters depend only on this interface, enabling implementation swaps without changing any calling code.

```
domain/port/RateLimiterPort.kt                    interface
infrastructure/InMemoryRateLimiter.kt             default — single-instance
infrastructure/redis/RedisRateLimiter.kt          opt-in — multi-replica, set KAUTH_REDIS_URL
```

`ServiceGraph` selects the implementation at startup. The InMemory adapter is the default; the Redis adapter is wired when `KAUTH_REDIS_URL` is set. See [REDIS.md](REDIS.md) and [ADR-12](adr/ADR-12-redis-sidecar.md).

### Port Interface

```kotlin
interface RateLimiterPort {
    val maxRequests: Int
    val windowSeconds: Long
    fun isAllowed(key: String): Boolean
    fun remaining(key: String): Int
    fun reset(key: String)
}
```

- `isAllowed(key)` — returns `true` if the request is within the limit, `false` if rate-limited.
- `remaining(key)` — returns how many requests remain in the current window.
- `reset(key)` — clears all state for a key (used for account unlocking and tests).

---

## In-Memory Implementation

The default `InMemoryRateLimiter` uses a sliding-window algorithm backed by a `ConcurrentHashMap`. This is the implementation used in single-instance deployments.

### How It Works

Each unique key gets a **bucket** containing a deque of timestamps. When `isAllowed` is called:

1. Timestamps older than the window are evicted from the deque.
2. If the remaining count is at or above `maxRequests`, the request is rejected.
3. Otherwise the current timestamp is appended and the request is allowed.

### Memory Management

The map is bounded by a configurable `maxKeys` cap (default: **10,000 keys**). When the map exceeds this cap, a two-phase eviction runs:

1. **Prune expired** — remove all buckets whose timestamps have fully expired (zero cost, these are dead entries).
2. **LRU eviction** — if still over capacity, evict the least-recently-accessed buckets until back under the cap.

This prevents unbounded memory growth during sustained attacks where an attacker generates thousands of unique source IPs.

### Trade-offs

| | |
|---|---|
| **Strengths** | Zero dependencies, no Redis required, trivial to deploy, sub-microsecond lookups |
| **Limitations** | Not distributed — each instance maintains its own window. Running 3 instances effectively triples the allowed attempts per key. |
| **Memory footprint** | ~200 bytes per tracked key. At the 10,000-key cap, ~2 MB. |

### When to Swap for Redis

If you run **multiple Kotauth instances** behind a load balancer, the in-memory rate limiter provides no cross-instance protection — each replica enforces the limit independently, so the effective ceiling becomes `configured limit × replica count`.

Set `KAUTH_REDIS_URL` to switch to the Redis-backed limiter. The `RateLimiterPort` interface is identical, so route and service code is unchanged. See [REDIS.md](REDIS.md) for configuration and operational details.

---

## Redis Implementation

When `KAUTH_REDIS_URL` is set, `RedisRateLimiter` replaces `InMemoryRateLimiter` for all four buckets. It uses the same sliding-window semantics so behavior is identical across the configured / unconfigured branch.

### How It Works

The check is implemented as a Lua script (`src/main/resources/redis/sliding_window.lua`) executed via `EVALSHA`:

1. `ZREMRANGEBYSCORE` evicts timestamps older than the window.
2. `ZCARD` reads the current count.
3. If the count is at or above `maxRequests`, return rejected.
4. Otherwise `ZADD` the request timestamp and `PEXPIRE` to refresh the bucket TTL.

The whole script is one round-trip and atomic — two concurrent requests cannot race past the limit.

### Fail-Closed Contract

When Redis is unreachable, `RedisRateLimiter.isAllowed` catches `RedisException` and returns `false` — the request is **rejected**. There is no silent fallback to a per-replica limiter. This is deliberate: a "fail-open" mode triggers exactly when the operator is least able to investigate. See [REDIS.md §"Fail-closed contract"](REDIS.md#fail-closed-contract) for the rationale.

The startup probe (`RedisHealthProbe`) catches a misconfigured or downed Redis before the server accepts a single request, so the runtime fail-closed branch is rare in steady-state operation.

### Key Format

```
kauth:rl:<bucket>:<key>
```

Where `<bucket>` is one of `login`, `register`, `token`, `mfa`. The `<key>` portion is the same `{ip}:{slug}` shape as in the InMemory implementation. The prefix lets multiple Kotauth deployments share a Redis instance safely if they namespace by Redis DB or use distinct hosts.

---

## Behavior on Rate Limit

When a request is rate-limited:

- **Login (`POST /authorize`)** — returns the login page with an error message asking the user to try again later.
- **MFA challenge (`POST /mfa-challenge`)** — returns the MFA page with an error message. The 5-attempt limit prevents brute-forcing the 6-digit TOTP code within the 5-minute MFA pending window.
- **Registration (`POST /register`)** — returns the registration page with an error message.
- **Forgot password (`POST /forgot-password`)** — silently redirects to the "sent" confirmation page (does not reveal whether the rate limit was hit, to prevent email enumeration).
- **Reset password (`POST /reset-password`)** — returns the reset page with an error message. Prevents repeated password attempts against a leaked reset token.
- **Token endpoint (`POST /token`)** — returns `429 Too Many Requests` as a JSON error response per OAuth2 conventions.

---

## Planned

The following endpoints are candidates for rate limiting in a future release:

| Endpoint | Attack vector | Priority |
|---|---|---|
| `POST /protocol/openid-connect/introspect` | Token oracle — reveals if tokens are active | Medium-High |
| `POST /protocol/openid-connect/revoke` | DB flood with random token strings | Medium |
| `POST /auth/social/complete-registration` | Username enumeration | Medium |
| `POST /account/mfa/verify` (portal enrollment) | TOTP brute-force with stolen session | Medium |
