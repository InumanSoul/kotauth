package com.kauth.domain.port

/** Outbound port for request rate limiting. See docs/RATE_LIMITING.md. */
interface RateLimiterPort {
    val maxRequests: Int
    val windowSeconds: Long

    fun isAllowed(key: String): Boolean

    fun remaining(key: String): Int

    fun reset(key: String)
}
