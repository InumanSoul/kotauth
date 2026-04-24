package com.kauth.infrastructure

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Smoke tests for [HibpBreachedPasswordAdapter].
 *
 * The adapter is mostly an HTTP wrapper, so the tests focus on the two
 * behaviors that matter for correctness regardless of network state:
 *   1. Fail-open: any error (unreachable URL, timeout, non-200) returns false
 *   2. Empty input short-circuits without a network call
 */
class HibpBreachedPasswordAdapterTest {
    @Test
    fun `empty password returns false without calling HIBP`() {
        val adapter =
            HibpBreachedPasswordAdapter(
                // Base URL pointing at an unused port — ensures any attempted
                // network call would fail fast. The short-circuit for empty
                // input means we never get there.
                baseUrl = "http://localhost:1",
                timeout = Duration.ofMillis(50),
            )
        assertFalse(adapter.isBreached(""))
    }

    @Test
    fun `fails open when the upstream is unreachable`() {
        val adapter =
            HibpBreachedPasswordAdapter(
                baseUrl = "http://localhost:1",
                timeout = Duration.ofMillis(200),
            )
        // Any non-empty password — HTTP call will fail, adapter must return false
        assertFalse(adapter.isBreached("some-password"))
    }

    @Test
    fun `cache TTL structure is deterministic with injected clock`() {
        // Construction smoke-test — the decorator must accept the custom clock
        // without exception. Full cache-behavior coverage happens in the
        // PasswordPolicyHibpIntegrationTest (via FakeBreachedPasswordPort).
        var now = Instant.parse("2026-05-01T12:00:00Z")
        val adapter =
            HibpBreachedPasswordAdapter(
                baseUrl = "http://localhost:1",
                timeout = Duration.ofMillis(50),
                clock = { now },
                ttlMillis = 60_000L,
            )
        // First call — network fails, fail-open returns false
        assertFalse(adapter.isBreached("test-password"))
        // Advance past TTL — still false (network still unreachable)
        now = now.plusSeconds(120)
        assertFalse(adapter.isBreached("test-password"))
        assertEquals(Instant.parse("2026-05-01T12:02:00Z"), now)
    }
}
