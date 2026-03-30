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
domain/port/RateLimiterPort.kt      interface
infrastructure/InMemoryRateLimiter.kt   default implementation (single-instance)
```

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

If you run **multiple Kotauth instances** behind a load balancer, the in-memory rate limiter provides no cross-instance protection. A Redis-backed implementation sharing state across instances is planned for a future release. The `RateLimiterPort` interface is designed for this swap — no route or service code changes required. See the [roadmap](ROADMAP.md) for timeline.

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
