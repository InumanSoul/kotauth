package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedProxiesTest {
    private val proxies = TrustedProxies.parse("173.245.48.0/20, 172.16.0.0/12, 2400:cb00::/32, 10.0.0.5")

    @Test
    fun `addresses inside a range or equal to a single entry are trusted`() {
        assertTrue(proxies.isTrusted("173.245.63.255"))
        assertTrue(proxies.isTrusted("172.20.0.3"))
        assertTrue(proxies.isTrusted("2400:cb00:1::1"))
        assertTrue(proxies.isTrusted("10.0.0.5"))
        assertFalse(proxies.isTrusted("173.245.64.0"))
        assertFalse(proxies.isTrusted("10.0.0.6"))
        assertFalse(proxies.isTrusted("2400:cb01::1"))
    }

    @Test
    fun `a hostname or garbage is never trusted and never looked up`() {
        assertFalse(proxies.isTrusted("localhost"))
        assertFalse(proxies.isTrusted("proxy.internal"))
        assertFalse(proxies.isTrusted("unknown"))
        assertFalse(proxies.isTrusted("999.1.1.1"))
    }

    @Test
    fun `the client is the rightmost untrusted entry`() {
        // Cloudflare edge appended by Traefik, client before it, spoofed value before that.
        assertEquals("203.0.113.9", proxies.clientFrom(listOf("6.6.6.6", " 203.0.113.9", " 173.245.48.7")))
    }

    @Test
    fun `a trusted caller's forwarded value is read when the caller is in the ranges`() {
        // A backend at 10.0.0.5 forwards its user's IP through the CDN.
        assertEquals("198.51.100.4", proxies.clientFrom(listOf("198.51.100.4", "10.0.0.5", "173.245.48.7")))
    }

    @Test
    fun `when every entry is trusted the leftmost is taken`() {
        assertEquals("172.16.0.9", proxies.clientFrom(listOf("172.16.0.9", "173.245.48.7")))
    }

    @Test
    fun `a malformed range is refused with the offending entry`() {
        val error = assertFailsWith<IllegalArgumentException> { TrustedProxies.parse("10.0.0.0/8, cloudflare") }
        assertTrue("cloudflare" in error.message!!)
        assertFailsWith<IllegalArgumentException> { TrustedProxies.parse("10.0.0.0/33") }
    }
}
