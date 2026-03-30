package com.kauth.infrastructure

import com.kauth.domain.port.RateLimiterPort
import java.util.concurrent.ConcurrentHashMap

/** In-memory sliding-window rate limiter. See docs/RATE_LIMITING.md. */
class InMemoryRateLimiter(
    override val maxRequests: Int,
    override val windowSeconds: Long,
    private val maxKeys: Int = MAX_KEYS_DEFAULT,
) : RateLimiterPort {
    private data class Bucket(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        var lastAccess: Long = 0L,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun isAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - (windowSeconds * 1_000L)

        if (buckets.size > maxKeys) {
            evict(windowStart)
        }

        val bucket = buckets.getOrPut(key) { Bucket() }

        synchronized(bucket) {
            while (bucket.timestamps.isNotEmpty() && bucket.timestamps.first() < windowStart) {
                bucket.timestamps.removeFirst()
            }
            bucket.lastAccess = now

            return if (bucket.timestamps.size >= maxRequests) {
                false
            } else {
                bucket.timestamps.addLast(now)
                true
            }
        }
    }

    override fun remaining(key: String): Int {
        val now = System.currentTimeMillis()
        val windowStart = now - (windowSeconds * 1_000L)
        val bucket = buckets[key] ?: return maxRequests
        synchronized(bucket) {
            val active = bucket.timestamps.count { it >= windowStart }
            return maxOf(0, maxRequests - active)
        }
    }

    override fun reset(key: String) {
        buckets.remove(key)
    }

    internal fun size(): Int = buckets.size

    private fun evict(windowStart: Long) {
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

        val excess = buckets.size - maxKeys
        if (excess > 0) {
            buckets.entries
                .sortedBy { it.value.lastAccess }
                .take(excess)
                .forEach { buckets.remove(it.key) }
        }
    }

    companion object {
        const val MAX_KEYS_DEFAULT = 10_000
    }
}
