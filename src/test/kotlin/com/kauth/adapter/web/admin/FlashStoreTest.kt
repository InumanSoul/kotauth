package com.kauth.adapter.web.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Unit tests for [FlashStore].
 *
 * Covers: round-trip put/take, one-time-use semantics, null/blank token guards,
 * and token uniqueness across distinct values.
 */
class FlashStoreTest {
    // =========================================================================
    // put / take round-trip
    // =========================================================================

    @Test
    fun `put and take round-trip returns stored value`() {
        val token = FlashStore.put("my-secret-value")
        val result = FlashStore.take(token)
        assertEquals("my-secret-value", result)
    }

    @Test
    fun `take returns null on second call with same token`() {
        val token = FlashStore.put("one-time")
        FlashStore.take(token) // first read consumes the entry
        val result = FlashStore.take(token)
        assertNull(result, "Second take on the same token must return null (one-time semantics)")
    }

    // =========================================================================
    // Guard clauses on null / blank tokens
    // =========================================================================

    @Test
    fun `take returns null for null token`() {
        assertNull(FlashStore.take(null))
    }

    @Test
    fun `take returns null for blank token`() {
        assertNull(FlashStore.take("   "))
    }

    @Test
    fun `take returns null for unknown token`() {
        assertNull(FlashStore.take("00000000-0000-0000-0000-000000000000"))
    }

    // =========================================================================
    // Token uniqueness
    // =========================================================================

    @Test
    fun `put returns distinct tokens for different values`() {
        val token1 = FlashStore.put("value-a")
        val token2 = FlashStore.put("value-b")
        assertNotEquals(token1, token2, "Each put call must produce a unique token")
        // Clean up so these entries do not bleed into other tests
        FlashStore.take(token1)
        FlashStore.take(token2)
    }
}
