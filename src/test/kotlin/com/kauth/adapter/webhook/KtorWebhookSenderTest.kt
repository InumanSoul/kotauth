package com.kauth.adapter.webhook

import com.kauth.domain.port.WebhookRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorWebhookSenderTest {
    private lateinit var server: HttpServer
    private val hookHits = AtomicInteger()
    private val redirectTargetHits = AtomicInteger()
    private var lastBody: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { ex ->
            hookHits.incrementAndGet()
            lastBody = ex.requestBody.readBytes().decodeToString()
            lastHeaders = ex.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
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
        val sender = KtorWebhookSender(WebhookAddressPolicy(allowPrivateNetworks = false))

        val result = runBlocking { sender.post(request("/hook")) }

        assertFalse(result.success)
        assertEquals(0, hookHits.get())
    }

    @Test
    fun `a loopback target is delivered to when private networks are allowed`() {
        val sender = KtorWebhookSender(WebhookAddressPolicy(allowPrivateNetworks = true))

        val result = runBlocking { sender.post(request("/hook")) }

        assertTrue(result.success)
        assertEquals(1, hookHits.get())
    }

    @Test
    fun `a redirect is not followed`() {
        val sender = KtorWebhookSender(WebhookAddressPolicy(allowPrivateNetworks = true))

        val result = runBlocking { sender.post(request("/redirect")) }

        assertFalse(result.success)
        assertEquals(307, result.httpStatus)
        assertEquals(0, redirectTargetHits.get())
    }

    @Test
    fun `the payload and the domain's headers arrive as sent`() {
        val sender = KtorWebhookSender(WebhookAddressPolicy(allowPrivateNetworks = true))
        val request =
            WebhookRequest(
                "$base/hook",
                """{"event":"user.created"}""",
                mapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "X-KotAuth-Event" to "user.created",
                    "X-KotAuth-Signature" to "sha256=abc",
                ),
            )

        val result = runBlocking { sender.post(request) }

        assertTrue(result.success)
        assertEquals("""{"event":"user.created"}""", lastBody)
        assertEquals("application/json; charset=utf-8", lastHeaders["content-type"])
        assertEquals("user.created", lastHeaders["x-kotauth-event"])
        assertEquals("sha256=abc", lastHeaders["x-kotauth-signature"])
    }

    @Test
    fun `the connection goes to the address the policy resolved`() {
        val policy = WebhookAddressPolicy(allowPrivateNetworks = true) { listOf(InetAddress.getByName("127.0.0.1")) }
        val sender = KtorWebhookSender(policy)

        val result =
            runBlocking {
                sender.post(
                    WebhookRequest("http://hooks.invalid:${server.address.port}/hook", "{}", emptyMap()),
                )
            }

        assertTrue(result.success)
        assertEquals(1, hookHits.get())
    }

    @Test
    fun `a host that turns internal after the endpoint was saved is not contacted`() {
        val answers = ArrayDeque(listOf("93.184.215.14", "127.0.0.1"))
        val policy =
            WebhookAddressPolicy(allowPrivateNetworks = false) { listOf(InetAddress.getByName(answers.removeFirst())) }
        val sender = KtorWebhookSender(policy)
        val url = "http://hooks.invalid:${server.address.port}/hook"

        val savedRefusal = sender.refusalFor(url)
        val result = runBlocking { sender.post(WebhookRequest(url, "{}", emptyMap())) }

        assertEquals(null, savedRefusal)
        assertFalse(result.success)
        assertEquals(0, hookHits.get())
    }
}
