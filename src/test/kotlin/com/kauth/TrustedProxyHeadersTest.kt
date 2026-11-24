package com.kauth

import com.kauth.infrastructure.TrustedProxies
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The deployed forwarded-header configuration, over a real socket so the peer is 127.0.0.1. */
class TrustedProxyHeadersTest {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @AfterTest
    fun stop() {
        server?.stop(0, 0)
    }

    private fun start(
        proxies: TrustedProxies?,
        clientIpHeader: String? = null,
    ): Int {
        val s =
            embeddedServer(Netty, port = 0, host = "127.0.0.1") {
                installTrustedProxyHeaders(proxies, clientIpHeader)
                routing { get("/ip") { call.respondText(call.request.origin.remoteAddress) } }
            }.start(wait = false)
        server = s
        return runBlocking {
            s.engine
                .resolvedConnectors()
                .single()
                .port
        }
    }

    private fun clientIp(
        port: Int,
        vararg headers: Pair<String, String>,
    ): String {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ip"))
        headers.forEach { (name, value) -> request.header(name, value) }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).body()
    }

    @Test
    fun `behind a CDN and a proxy the client is the first address outside the trusted ranges`() {
        val port = start(TrustedProxies.parse("127.0.0.0/8, 173.245.48.0/20"))

        assertEquals("203.0.113.9", clientIp(port, "X-Forwarded-For" to "6.6.6.6, 203.0.113.9, 173.245.48.7"))
    }

    @Test
    fun `headers from a peer outside the ranges are ignored`() {
        val port = start(TrustedProxies.parse("173.245.48.0/20"))

        assertEquals("127.0.0.1", clientIp(port, "X-Forwarded-For" to "203.0.113.9"))
    }

    @Test
    fun `a configured client IP header from a trusted peer wins`() {
        val port = start(TrustedProxies.parse("127.0.0.0/8"), clientIpHeader = "CF-Connecting-IP")

        assertEquals(
            "198.51.100.4",
            clientIp(port, "CF-Connecting-IP" to "198.51.100.4", "X-Forwarded-For" to "198.51.100.4, 173.245.48.7"),
        )
        assertEquals("203.0.113.9", clientIp(port, "X-Forwarded-For" to "203.0.113.9"))
    }

    @Test
    fun `without ranges the last forwarded entry is used, as before`() {
        val port = start(null)

        assertEquals("173.245.48.7", clientIp(port, "X-Forwarded-For" to "203.0.113.9, 173.245.48.7"))
    }
}
