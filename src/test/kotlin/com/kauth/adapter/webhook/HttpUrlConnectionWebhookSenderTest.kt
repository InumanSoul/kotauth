package com.kauth.adapter.webhook

import com.kauth.domain.port.WebhookRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpUrlConnectionWebhookSenderTest {
    private lateinit var server: HttpServer
    private val hookHits = AtomicInteger()
    private val redirectTargetHits = AtomicInteger()

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { ex ->
            hookHits.incrementAndGet()
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "$base/target")
            ex.sendResponseHeaders(307, -1)
            ex.close()
        }
        server.createContext("/target") { ex ->
            redirectTargetHits.incrementAndGet()
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun request(path: String) = WebhookRequest("$base$path", "{}", mapOf("Content-Type" to "application/json"))

    @Test
    fun `a loopback target is never contacted by default`() {
        val sender = HttpUrlConnectionWebhookSender(WebhookAddressPolicy(allowPrivateNetworks = false))

        val result = sender.post(request("/hook"))

        assertFalse(result.success)
        assertEquals(0, hookHits.get())
    }

    @Test
    fun `a loopback target is delivered to when private networks are allowed`() {
        val sender = HttpUrlConnectionWebhookSender(WebhookAddressPolicy(allowPrivateNetworks = true))

        val result = sender.post(request("/hook"))

        assertTrue(result.success)
        assertEquals(1, hookHits.get())
    }

    @Test
    fun `a redirect is not followed`() {
        val sender = HttpUrlConnectionWebhookSender(WebhookAddressPolicy(allowPrivateNetworks = true))

        val result = sender.post(request("/redirect"))

        assertFalse(result.success)
        assertEquals(307, result.httpStatus)
        assertEquals(0, redirectTargetHits.get())
    }
}
