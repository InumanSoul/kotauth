package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [InMemoryRateLimiter] — in-memory sliding-window rate limiter.
 *
 * These tests exercise the core contract: allow up to [maxRequests] within
 * [windowSeconds], then reject. Also tests key isolation, remaining count,
 * and reset behavior.
 */
class RateLimiterTest {
    // =========================================================================
    // isAllowed — basic flow
    // =========================================================================

    @Test
    fun `isAllowed - permits requests up to the limit`() {
        val limiter = InMemoryRateLimiter(maxRequests = 3, windowSeconds = 60)

        assertTrue(limiter.isAllowed("key1"), "1st request should be allowed")
        assertTrue(limiter.isAllowed("key1"), "2nd request should be allowed")
        assertTrue(limiter.isAllowed("key1"), "3rd request should be allowed")
    }

    @Test
    fun `isAllowed - rejects requests beyond the limit`() {
        val limiter = InMemoryRateLimiter(maxRequests = 3, windowSeconds = 60)

        repeat(3) { limiter.isAllowed("key1") }
        assertFalse(limiter.isAllowed("key1"), "4th request should be rate-limited")
        assertFalse(limiter.isAllowed("key1"), "5th request should also be rate-limited")
    }

    @Test
    fun `isAllowed - limit of 1 blocks immediately after first request`() {
        val limiter = InMemoryRateLimiter(maxRequests = 1, windowSeconds = 60)

        assertTrue(limiter.isAllowed("key1"))
        assertFalse(limiter.isAllowed("key1"))
    }

    // =========================================================================
    // Key isolation
    // =========================================================================

    @Test
    fun `isAllowed - different keys have independent limits`() {
        val limiter = InMemoryRateLimiter(maxRequests = 2, windowSeconds = 60)

        assertTrue(limiter.isAllowed("user:1"))
        assertTrue(limiter.isAllowed("user:1"))
        assertFalse(limiter.isAllowed("user:1"), "user:1 should be exhausted")

        assertTrue(limiter.isAllowed("user:2"), "user:2 should have its own quota")
        assertTrue(limiter.isAllowed("user:2"))
    }

    // =========================================================================
    // remaining
    // =========================================================================

    @Test
    fun `remaining - returns maxRequests for unknown key`() {
        val limiter = InMemoryRateLimiter(maxRequests = 5, windowSeconds = 60)
        assertEquals(5, limiter.remaining("never-seen"))
    }

    @Test
    fun `remaining - decrements with each allowed request`() {
        val limiter = InMemoryRateLimiter(maxRequests = 3, windowSeconds = 60)

        assertEquals(3, limiter.remaining("key1"))
        limiter.isAllowed("key1")
        assertEquals(2, limiter.remaining("key1"))
        limiter.isAllowed("key1")
        assertEquals(1, limiter.remaining("key1"))
        limiter.isAllowed("key1")
        assertEquals(0, limiter.remaining("key1"))
    }

    @Test
    fun `remaining - never goes below zero`() {
        val limiter = InMemoryRateLimiter(maxRequests = 1, windowSeconds = 60)
        limiter.isAllowed("key1")
        limiter.isAllowed("key1") // rejected but shouldn't go negative
        assertEquals(0, limiter.remaining("key1"))
    }

    // =========================================================================
    // reset
    // =========================================================================

    @Test
    fun `reset - restores full quota for the key`() {
        val limiter = InMemoryRateLimiter(maxRequests = 2, windowSeconds = 60)

        limiter.isAllowed("key1")
        limiter.isAllowed("key1")
        assertFalse(limiter.isAllowed("key1"), "Should be rate-limited before reset")

        limiter.reset("key1")

        assertTrue(limiter.isAllowed("key1"), "Should be allowed after reset")
        assertEquals(1, limiter.remaining("key1"))
    }

    @Test
    fun `reset - does not affect other keys`() {
        val limiter = InMemoryRateLimiter(maxRequests = 2, windowSeconds = 60)

        limiter.isAllowed("key1")
        limiter.isAllowed("key2")
        limiter.isAllowed("key2")

        limiter.reset("key1")

        assertFalse(limiter.isAllowed("key2"), "key2 should still be rate-limited")
        assertEquals(2, limiter.remaining("key1"), "key1 should be fully reset")
    }

    // =========================================================================
    // Constructor properties
    // =========================================================================

    @Test
    fun `constructor properties are accessible`() {
        val limiter = InMemoryRateLimiter(maxRequests = 10, windowSeconds = 120)
        assertEquals(10, limiter.maxRequests)
        assertEquals(120L, limiter.windowSeconds)
    }
}
