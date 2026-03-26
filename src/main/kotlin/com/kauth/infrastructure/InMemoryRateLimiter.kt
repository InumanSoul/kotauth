package com.kauth.infrastructure

import com.kauth.domain.port.RateLimiterPort
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory sliding-window rate limiter — implements [RateLimiterPort].
 *
 * Keyed by an arbitrary string (e.g. "IP:endpoint"). Tracks how many requests
 * a key has made in the last [windowSeconds] seconds and rejects once it exceeds
 * [maxRequests].
 *
 * Trade-offs:
 *   + Zero dependencies, no Redis required, trivial to deploy.
 *   - Not distributed — each instance has its own window. Acceptable for single-
 *     instance deployments; swap for a Redis-backed implementation if clustering.
 *   - Memory grows with unique keys. A cleanup job prunes idle buckets on each
 *     check (probabilistic, not scheduled) to keep footprint bounded.
 */
class InMemoryRateLimiter(
    override val maxRequests: Int,
    override val windowSeconds: Long,
) : RateLimiterPort {
    private data class Bucket(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        val hitCount: AtomicInteger = AtomicInteger(0),
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * Returns true if the request is allowed, false if it has been rate-limited.
     */
    override fun isAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - (windowSeconds * 1_000L)

        // Probabilistic prune: when the map exceeds 1000 keys, evict idle buckets
        if (buckets.size > 1_000) {
            pruneIdleBuckets(windowStart)
        }

        val bucket = buckets.getOrPut(key) { Bucket() }

        synchronized(bucket) {
            // Evict timestamps outside the window
            while (bucket.timestamps.isNotEmpty() && bucket.timestamps.first() < windowStart) {
                bucket.timestamps.removeFirst()
            }

            return if (bucket.timestamps.size >= maxRequests) {
                false
            } else {
                bucket.timestamps.addLast(now)
                true
            }
        }
    }

    /** Removes buckets whose timestamps have all expired. */
    private fun pruneIdleBuckets(windowStart: Long) {
        val iter = buckets.entries.iterator()
        while (iter.hasNext()) {
            val (_, bucket) = iter.next()
            synchronized(bucket) {
                while (bucket.timestamps.isNotEmpty() && bucket.timestamps.first() < windowStart) {
                    bucket.timestamps.removeFirst()
                }
                if (bucket.timestamps.isEmpty()) iter.remove()
            }
        }
    }

    /**
     * Returns the number of remaining requests for the given key in the current window.
     */
    override fun remaining(key: String): Int {
        val now = System.currentTimeMillis()
        val windowStart = now - (windowSeconds * 1_000L)
        val bucket = buckets[key] ?: return maxRequests
        synchronized(bucket) {
            val active = bucket.timestamps.count { it >= windowStart }
            return maxOf(0, maxRequests - active)
        }
    }

    /**
     * Clears all state for a key — useful for tests or explicit user unlocking.
     */
    override fun reset(key: String) {
        buckets.remove(key)
    }
}
