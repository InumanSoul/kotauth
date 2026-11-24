package com.kauth.adapter.webhook

import com.kauth.domain.port.WebhookRequest
import com.kauth.domain.port.WebhookSendResult
import com.kauth.domain.port.WebhookSenderPort
import org.slf4j.LoggerFactory
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * Webhook transport over `java.net.HttpURLConnection`.
 *
 * Moved out of `WebhookService` unchanged: this code previously ran in the domain layer, which
 * broke ADR-01's rule that `domain/` imports no framework or I/O. Behaviour is identical — same
 * timeouts, same 2xx-is-success rule, same swallow-and-report on failure.
 *
 * The connection is deliberately blocking, matching what it replaced. Migrating to a non-blocking
 * client is tracked separately; doing it here would have hidden a behaviour change inside a move.
 */
class HttpUrlConnectionWebhookSender(
    private val addressPolicy: WebhookAddressPolicy,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : WebhookSenderPort {
    private val log = LoggerFactory.getLogger(HttpUrlConnectionWebhookSender::class.java)

    override fun refusalFor(url: String): String? = addressPolicy.refusalFor(url)

    override fun post(request: WebhookRequest): WebhookSendResult {
        // Checked again at send time: DNS may have changed since the endpoint was saved. The JVM's
        // positive DNS cache makes the connection below see the addresses this check approved.
        addressPolicy.refusalFor(request.url)?.let { reason ->
            log.warn("Webhook refused: url=${request.url}: $reason")
            return WebhookSendResult(null, false)
        }
        return try {
            val conn = URI(request.url).toURL().openConnection() as HttpURLConnection
            // A redirect could lead anywhere, past the address check above.
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            request.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(request.payload) }

            val status = conn.responseCode
            WebhookSendResult(status, status in 200..299)
        } catch (e: Exception) {
            // A transport failure is a delivery outcome, not an exception — the caller records it
            // and retries. The URL is operator-supplied, so it is logged; the payload never is.
            log.warn("Webhook HTTP error: url=${request.url}: ${e.message}")
            WebhookSendResult(null, false)
        }
    }
}
