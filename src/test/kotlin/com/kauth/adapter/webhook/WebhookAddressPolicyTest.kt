package com.kauth.adapter.webhook

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookAddressPolicyTest {
    private fun policy(
        allowPrivate: Boolean = false,
        vararg resolved: String,
    ) = WebhookAddressPolicy(allowPrivate) { host ->
        if (host == "nowhere.example") throw UnknownHostException(host)
        if (resolved.isEmpty()) listOf(InetAddress.getByName(host)) else resolved.map { InetAddress.getByName(it) }
    }

    private val privateTargets =
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.10", "100.64.0.1", "::1", "fc00::1", "fd12::5")

    private val alwaysRefused = listOf("169.254.169.254", "fd00:ec2::254", "fe80::1", "0.0.0.0", "224.0.0.1")

    @Test
    fun `a public address is allowed`() {
        assertNull(policy(false, "93.184.216.34").refusalFor("https://hooks.example.com/kotauth"))
    }

    @Test
    fun `private and loopback addresses are refused by default`() {
        for (ip in privateTargets) {
            assertNotNull(policy(false, ip).refusalFor("https://hooks.example.com/x"), "$ip should be refused")
        }
    }

    @Test
    fun `private and loopback addresses are allowed when the operator opts in`() {
        for (ip in privateTargets) {
            assertNull(policy(true, ip).refusalFor("http://app:8080/hooks"), "$ip should be allowed")
        }
    }

    @Test
    fun `cloud metadata and link-local addresses are refused even when private networks are allowed`() {
        for (ip in alwaysRefused) {
            assertNotNull(policy(true, ip).refusalFor("http://metadata.example/latest"), "$ip should be refused")
        }
    }

    @Test
    fun `a host is refused if any of its addresses is refused`() {
        val reason = policy(false, "93.184.216.34", "10.0.0.7").refusalFor("https://split.example/x")
        assertNotNull(reason)
        assertTrue(reason.contains("KAUTH_WEBHOOK_ALLOW_PRIVATE_NETWORKS"))
    }

    @Test
    fun `literal IP hosts are checked, including bracketed IPv6`() {
        assertNotNull(policy().refusalFor("http://169.254.169.254/latest/meta-data"))
        assertNotNull(policy().refusalFor("http://[::1]:9000/hook"))
        assertNotNull(policy().refusalFor("http://[::ffff:127.0.0.1]/hook"))
    }

    @Test
    fun `unresolvable hosts, other schemes and malformed URLs are refused`() {
        assertNotNull(policy().refusalFor("https://nowhere.example/x"))
        assertNotNull(policy(false, "93.184.216.34").refusalFor("ftp://hooks.example.com/x"))
        assertNotNull(policy().refusalFor("not a url"))
    }
}
