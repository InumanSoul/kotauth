package com.kauth.domain.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 8252 §7.3 loopback matching.
 *
 * The relaxation is deliberately narrow: only `http` on a loopback *literal*, only the
 * port is ignored, and only for clients that opted in (public clients). Every test that
 * asserts `false` here is guarding a way the exception could be widened by accident.
 */
class RedirectUriMatcherTest {
    private val registered = listOf("http://127.0.0.1:47821/cb")

    // -------------------------------------------------------------------------
    // The behaviour the RFC requires
    // -------------------------------------------------------------------------

    @Test
    fun `loopback matches a different ephemeral port`() {
        assertTrue(RedirectUriMatcher.matches(registered, "http://127.0.0.1:58139/cb", allowAnyLoopbackPort = true))
    }

    @Test
    fun `loopback matches when the registered URI carries no port at all`() {
        assertTrue(
            RedirectUriMatcher.matches(
                listOf("http://127.0.0.1/cb"),
                "http://127.0.0.1:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `IPv6 loopback matches a different port`() {
        assertTrue(
            RedirectUriMatcher.matches(
                listOf("http://[::1]:47821/cb"),
                "http://[::1]:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `an exact match still works with the relaxation enabled`() {
        assertTrue(RedirectUriMatcher.matches(registered, "http://127.0.0.1:47821/cb", allowAnyLoopbackPort = true))
    }

    // -------------------------------------------------------------------------
    // The relaxation must not widen past loopback
    // -------------------------------------------------------------------------

    @Test
    fun `localhost is excluded — it resolves through the name service and can be redirected`() {
        assertFalse(
            RedirectUriMatcher.matches(
                listOf("http://localhost:47821/cb"),
                "http://localhost:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `https loopback is not loosened`() {
        assertFalse(
            RedirectUriMatcher.matches(
                listOf("https://127.0.0.1:47821/cb"),
                "https://127.0.0.1:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `a real host is never port-agnostic`() {
        assertFalse(
            RedirectUriMatcher.matches(
                listOf("http://app.example.com:8080/cb"),
                "http://app.example.com:9090/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `a host that merely starts with the loopback literal is not loopback`() {
        assertFalse(
            RedirectUriMatcher.matches(
                listOf("http://127.0.0.1.evil.example.com:47821/cb"),
                "http://127.0.0.1.evil.example.com:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `another loopback-range address is not accepted`() {
        assertFalse(
            RedirectUriMatcher.matches(
                listOf("http://127.0.0.2:47821/cb"),
                "http://127.0.0.2:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Only the port is ignored — everything else still has to match
    // -------------------------------------------------------------------------

    @Test
    fun `path must still match`() {
        assertFalse(RedirectUriMatcher.matches(registered, "http://127.0.0.1:58139/other", allowAnyLoopbackPort = true))
    }

    @Test
    fun `query must still match`() {
        assertFalse(
            RedirectUriMatcher.matches(
                listOf("http://127.0.0.1:47821/cb?state=fixed"),
                "http://127.0.0.1:58139/cb?state=other",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `userinfo cannot be smuggled in`() {
        assertFalse(
            RedirectUriMatcher.matches(
                registered,
                "http://evil@127.0.0.1:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `a loopback host cannot match a registered non-loopback URI`() {
        assertFalse(
            RedirectUriMatcher.matches(
                listOf("http://app.example.com/cb"),
                "http://127.0.0.1:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Opt-in gate
    // -------------------------------------------------------------------------

    @Test
    fun `without the flag a differing port is refused`() {
        assertFalse(RedirectUriMatcher.matches(registered, "http://127.0.0.1:58139/cb", allowAnyLoopbackPort = false))
    }

    @Test
    fun `without the flag an exact match still works`() {
        assertTrue(RedirectUriMatcher.matches(registered, "http://127.0.0.1:47821/cb", allowAnyLoopbackPort = false))
    }

    // -------------------------------------------------------------------------
    // Malformed input must not throw or match
    // -------------------------------------------------------------------------

    @Test
    fun `a malformed requested URI does not match and does not throw`() {
        assertFalse(
            RedirectUriMatcher.matches(registered, "http://127.0.0.1:not-a-port/cb", allowAnyLoopbackPort = true),
        )
        assertFalse(RedirectUriMatcher.matches(registered, ":::::", allowAnyLoopbackPort = true))
        assertFalse(RedirectUriMatcher.matches(registered, "", allowAnyLoopbackPort = true))
    }

    @Test
    fun `a malformed registered URI is skipped rather than throwing`() {
        assertTrue(
            RedirectUriMatcher.matches(
                listOf(":::::", "http://127.0.0.1:47821/cb"),
                "http://127.0.0.1:58139/cb",
                allowAnyLoopbackPort = true,
            ),
        )
    }

    @Test
    fun `no registered URIs means no match`() {
        assertFalse(RedirectUriMatcher.matches(emptyList(), "http://127.0.0.1:58139/cb", allowAnyLoopbackPort = true))
    }
}
