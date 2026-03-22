package com.kauth.domain.port

/**
 * Port (outbound) — defines what the domain needs from a rate limiter.
 *
 * Implementations handle the storage and eviction strategy. The route
 * adapters work exclusively with this abstraction, enabling a swap from
 * in-memory to Redis (or any distributed store) without touching any
 * calling code.
 *
 * Implemented by [InMemoryRateLimiter].
 */
interface RateLimiterPort {
    val maxRequests: Int
    val windowSeconds: Long

    /** Returns true if the request is allowed, false if rate-limited. */
    fun isAllowed(key: String): Boolean

    /** Returns the number of remaining requests for [key] in the current window. */
    fun remaining(key: String): Int

    /** Clears all state for [key] — useful for tests or explicit user unlocking. */
    fun reset(key: String)
}
