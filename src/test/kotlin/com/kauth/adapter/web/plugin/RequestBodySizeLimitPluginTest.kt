package com.kauth.adapter.web.plugin

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [requestBodySizeLimitPlugin] is the fix for "no request-body size limit anywhere" — every
 * endpoint used to accept an unbounded body. Exercised against a minimal echo route rather than
 * the full server, since the behaviour under test lives entirely in the plugin's
 * `onCallReceive`/`transformBody` interception, not in any particular route.
 */
class RequestBodySizeLimitPluginTest {
    private fun setup(
        maxBytes: Long,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(requestBodySizeLimitPlugin(maxBytes))
            install(StatusPages) {
                exception<PayloadTooLargeException> { call, cause ->
                    call.respond(HttpStatusCode.PayloadTooLarge, cause.message ?: "too large")
                }
            }
            routing {
                post("/echo") {
                    val body = call.receiveText()
                    call.respondText(body.length.toString())
                }
            }
        }
        block(client)
    }

    /** Streamed content with no declared `contentLength` — the client sends it without a
     * Content-Length header, forcing the server to rely on the actual-bytes-read cap rather than
     * the cheap header check. This is the scenario a chunked request produces. */
    private fun streamedBody(text: String): OutgoingContent.WriteChannelContent =
        object : OutgoingContent.WriteChannelContent() {
            override val contentLength: Long? = null

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeStringUtf8(text)
            }
        }

    @Test
    fun `a request body under the limit succeeds`() =
        setup(maxBytes = 1_000) { client ->
            val response = client.post("/echo") { setBody("a".repeat(500)) }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `a request body over the limit is rejected with 413`() =
        setup(maxBytes = 1_000) { client ->
            val response = client.post("/echo") { setBody("a".repeat(2_000)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `a chunked request with no Content-Length header is still bounded`() =
        setup(maxBytes = 1_000) { client ->
            val response =
                client.post("/echo") {
                    setBody(streamedBody("a".repeat(2_000)))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `a chunked request under the limit still succeeds`() =
        setup(maxBytes = 1_000) { client ->
            val response =
                client.post("/echo") {
                    setBody(streamedBody("a".repeat(500)))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("500", response.bodyAsText())
        }
}
