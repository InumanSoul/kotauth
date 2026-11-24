package com.kauth.adapter.webhook

import com.kauth.domain.port.WebhookRequest
import com.kauth.domain.port.WebhookSendResult
import com.kauth.domain.port.WebhookSenderPort
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import org.apache.hc.client5.http.DnsResolver
import org.slf4j.LoggerFactory
import java.net.InetAddress
import kotlin.coroutines.cancellation.CancellationException

/**
 * Webhook transport over a non-blocking Ktor client (Apache5 engine).
 *
 * The engine resolves hosts through [addressPolicy], so the connection is made to exactly the
 * addresses the policy approved. Redirects are never followed: one could lead past that check.
 */
class KtorWebhookSender(
    private val addressPolicy: WebhookAddressPolicy,
    connectTimeoutMs: Long = 5_000,
    socketTimeoutMs: Int = 10_000,
) : WebhookSenderPort {
    private val log = LoggerFactory.getLogger(KtorWebhookSender::class.java)

    private val client =
        HttpClient(Apache5) {
            expectSuccess = false
            followRedirects = false
            engine {
                followRedirects = false
                connectTimeout = connectTimeoutMs
                socketTimeout = socketTimeoutMs
                dnsResolver = PolicyDnsResolver(addressPolicy)
            }
        }

    override fun refusalFor(url: String): String? = addressPolicy.refusalFor(url)

    override suspend fun post(request: WebhookRequest): WebhookSendResult =
        try {
            val contentType = request.headers.entries.firstOrNull { it.key.equals(HttpHeaders.ContentType, true) }
            val response =
                client.post(request.url) {
                    headers {
                        request.headers
                            .filterKeys { !it.equals(HttpHeaders.ContentType, true) }
                            .forEach { (name, value) -> append(name, value) }
                    }
                    setBody(
                        TextContent(
                            request.payload,
                            contentType?.let { ContentType.parse(it.value) } ?: ContentType.Application.Json,
                        ),
                    )
                }
            val status = response.status.value
            WebhookSendResult(status, status in 200..299)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A transport failure, a refused address included, is a delivery outcome: the caller
            // records it and retries. The URL is operator-supplied, so it is logged; the payload never is.
            log.warn("Webhook HTTP error: url=${request.url}: ${e.message}")
            WebhookSendResult(null, false)
        }
}

private class PolicyDnsResolver(
    private val policy: WebhookAddressPolicy,
) : DnsResolver {
    override fun resolve(host: String): Array<InetAddress> = policy.allowedAddresses(host).toTypedArray()

    override fun resolveCanonicalHostname(host: String): String = host
}
