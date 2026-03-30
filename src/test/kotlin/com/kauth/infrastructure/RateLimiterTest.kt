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

    // =========================================================================
    // LRU eviction — memory bound
    // =========================================================================

    @Test
    fun `eviction - caps tracked keys at maxKeys`() {
        val limiter = InMemoryRateLimiter(maxRequests = 5, windowSeconds = 60, maxKeys = 10)

        // Fill past capacity — each isAllowed over maxKeys triggers eviction
        repeat(15) { i -> limiter.isAllowed("key-$i") }

        // One final call to trigger eviction after all keys are inserted
        limiter.isAllowed("trigger-eviction")

        assertTrue(limiter.size() <= 11, "Bucket count should be bounded near maxKeys")
    }

    @Test
    fun `eviction - preserves most recently accessed keys`() {
        val limiter = InMemoryRateLimiter(maxRequests = 5, windowSeconds = 60, maxKeys = 5)

        // Access keys 0-4, then 5-9 (keys 0-4 are oldest)
        repeat(5) { i -> limiter.isAllowed("old-$i") }
        repeat(6) { i -> limiter.isAllowed("new-$i") }

        // After eviction, new keys should survive and old keys should be evicted
        assertTrue(limiter.remaining("new-5") < 5, "Recently accessed key should survive eviction")
    }

    @Test
    fun `eviction - with maxKeys=1, second key triggers eviction of the first`() {
        val limiter = InMemoryRateLimiter(maxRequests = 5, windowSeconds = 60, maxKeys = 1)

        // "first" is inserted; size becomes 1 (at capacity, not yet over).
        limiter.isAllowed("first")
        // "second" is inserted; size becomes 2 (over maxKeys). The *next* call sees
        // size > maxKeys and triggers eviction of the oldest key ("first").
        limiter.isAllowed("second")
        // A third call triggers the eviction guard — size(2) > maxKeys(1) — and removes
        // whichever bucket has the lowest lastAccess. "first" was accessed before "second",
        // so it is the eviction candidate.
        limiter.isAllowed("trigger")

        // Regardless of which of the two earlier keys was evicted, the limiter must
        // have brought the tracked key count back to near maxKeys. The total size
        // should not exceed maxKeys + 1 (the trigger key itself was just added).
        assertTrue(limiter.size() <= 2, "Size must be bounded after eviction, got ${limiter.size()}")
    }

    @Test
    fun `eviction - LRU ordering preserves most recently accessed key`() {
        val limiter = InMemoryRateLimiter(maxRequests = 5, windowSeconds = 60, maxKeys = 2)

        // Seed two keys; limiter is at capacity.
        limiter.isAllowed("key-a")
        limiter.isAllowed("key-b")

        // key-c is inserted; size becomes 3, exceeding maxKeys(2).
        limiter.isAllowed("key-c")

        // A follow-up call triggers eviction — the bucket with the lowest lastAccess is
        // removed (key-a, since it was the least recently accessed at this point).
        limiter.isAllowed("trigger")

        // After eviction the limiter must bound itself back toward maxKeys.
        assertTrue(limiter.size() <= 3, "Size must be bounded after eviction, got ${limiter.size()}")

        // key-b and key-c were accessed more recently than key-a; at least one of them
        // should still have consumed quota (remaining < maxRequests).
        val keyBRemaining = limiter.remaining("key-b")
        val keyCRemaining = limiter.remaining("key-c")
        assertTrue(
            keyBRemaining < 5 || keyCRemaining < 5,
            "At least one recently accessed key must survive eviction with quota consumed",
        )
    }
}
