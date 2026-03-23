package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookDeliveryStatus
import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.model.WebhookEvent
import com.kauth.domain.port.WebhookDeliveryRepository
import com.kauth.domain.port.WebhookEndpointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook delivery service.
 *
 * Responsibilities:
 *   1. Fan out an event to all enabled, subscribed endpoints for a tenant.
 *   2. Sign each payload with HMAC-SHA256 (`X-KotAuth-Signature: sha256=<hex>`).
 *   3. Attempt delivery asynchronously using a background coroutine scope.
 *   4. Retry on failure: immediate → 5 min → 30 min (3 attempts max).
 *
 * Thread safety: [dispatch] is safe to call from any thread. The coroutine
 * scope is daemon-like — it does not prevent JVM shutdown.
 *
 * Failure policy: a failed delivery after all retries is marked FAILED and
 * left in the database for operator inspection. It does not affect the auth
 * flow in any way.
 */
class WebhookService(
    private val endpointRepository: WebhookEndpointRepository,
    private val deliveryRepository: WebhookDeliveryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Retry delay schedule in milliseconds: immediate, 5 min, 30 min
    private val retryDelaysMs = listOf(0L, 5 * 60_000L, 30 * 60_000L)
    private val maxAttempts = retryDelaysMs.size

    // HTTP timeout for each delivery attempt
    private val connectTimeoutMs = 5_000
    private val readTimeoutMs = 10_000

    // ==========================================================================
    // Public API
    // ==========================================================================

    /**
     * Fan out [eventType] to all enabled, subscribed endpoints for [tenantId].
     *
     * Builds the payload JSON, persists a [WebhookDelivery] record per endpoint,
     * then fires each delivery asynchronously. Returns immediately — callers are
     * never blocked by webhook delivery.
     *
     * @param tenantId    The tenant whose endpoints should receive this event.
     * @param eventType   One of the [WebhookEvent] constants (e.g. "user.created").
     * @param payloadData Key-value pairs to include in the `data` object of the payload.
     */
    fun dispatch(
        tenantId: TenantId,
        eventType: String,
        payloadData: Map<String, Any?> = emptyMap(),
    ) {
        val endpoints =
            try {
                endpointRepository.findEnabledByTenantAndEvent(tenantId, eventType)
            } catch (e: Exception) {
                log.error("Webhook dispatch: failed to query endpoints for tenant=${tenantId.value} event=$eventType", e)
                return
            }

        if (endpoints.isEmpty()) return

        val payload = buildPayload(eventType, payloadData)

        endpoints.forEach { endpoint ->
            val delivery =
                try {
                    deliveryRepository.save(
                        WebhookDelivery(
                            endpointId = endpoint.id!!,
                            eventType = eventType,
                            payload = payload,
                        ),
                    )
                } catch (e: Exception) {
                    log.error("Webhook dispatch: failed to persist delivery record for endpoint=${endpoint.id}", e)
                    return@forEach
                }

            scope.launch {
                attemptDelivery(endpoint, delivery, attemptNumber = 0)
            }
        }
    }

    /**
     * Registers a new webhook endpoint for [tenantId].
     *
     * @param url         The target URL (must be http:// or https://)
     * @param events      Set of [WebhookEvent] names to subscribe to
     * @param description Optional label for the admin UI
     * @return [WebhookResult.Success] with the saved endpoint, or [WebhookResult.Failure]
     */
    fun createEndpoint(
        tenantId: TenantId,
        url: String,
        events: Set<String>,
        description: String = "",
    ): WebhookResult {
        if (url.isBlank()) return WebhookResult.Failure("URL cannot be blank.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return WebhookResult.Failure("URL must start with http:// or https://.")
        }
        if (url.length > 2048) return WebhookResult.Failure("URL must be 2048 characters or fewer.")
        val unknownEvents = events.filter { it !in WebhookEvent.ALL }
        if (unknownEvents.isNotEmpty()) {
            return WebhookResult.Failure("Unknown event types: ${unknownEvents.joinToString(", ")}.")
        }

        val secret = generateSecret()
        val saved =
            endpointRepository.save(
                WebhookEndpoint(
                    tenantId = tenantId,
                    url = url,
                    secret = secret,
                    events = events,
                    description = description,
                ),
            )
        return WebhookResult.Success(saved, plaintextSecret = secret)
    }

    fun listEndpoints(tenantId: TenantId): List<WebhookEndpoint> = endpointRepository.findByTenantId(tenantId)

    fun deleteEndpoint(
        id: Int,
        tenantId: TenantId,
    ) = endpointRepository.delete(id, tenantId)

    fun toggleEndpoint(
        id: Int,
        tenantId: TenantId,
        enabled: Boolean,
    ) = endpointRepository.setEnabled(id, tenantId, enabled)

    fun recentDeliveries(
        tenantId: TenantId,
        limit: Int = 50,
    ): List<WebhookDelivery> = deliveryRepository.findByTenantId(tenantId, limit)

    fun deliveriesForEndpoint(
        endpointId: Int,
        limit: Int = 50,
    ): List<WebhookDelivery> = deliveryRepository.findByEndpointId(endpointId, limit)

    // ==========================================================================
    // Delivery machinery (internal)
    // ==========================================================================

    private suspend fun attemptDelivery(
        endpoint: WebhookEndpoint,
        delivery: WebhookDelivery,
        attemptNumber: Int,
    ) {
        if (attemptNumber >= maxAttempts) return

        val delayMs = retryDelaysMs[attemptNumber]
        if (delayMs > 0) delay(delayMs)

        val (responseStatus, success) = sendRequest(endpoint, delivery.payload)
        val now = Instant.now()
        val newAttempts = delivery.attempts + 1
        val newStatus =
            when {
                success -> WebhookDeliveryStatus.DELIVERED
                newAttempts >= maxAttempts -> WebhookDeliveryStatus.FAILED
                else -> WebhookDeliveryStatus.PENDING
            }

        val updated =
            delivery.copy(
                status = newStatus,
                attempts = newAttempts,
                lastAttemptAt = now,
                responseStatus = responseStatus,
            )

        try {
            deliveryRepository.update(updated)
        } catch (e: Exception) {
            log.error("Webhook: failed to persist delivery update for id=${delivery.id}", e)
        }

        if (!success && newAttempts < maxAttempts) {
            // Schedule next retry (the delivery object passed retains the endpointId/payload)
            scope.launch {
                attemptDelivery(endpoint, updated, attemptNumber + 1)
            }
        } else if (!success) {
            log.warn(
                "Webhook delivery FAILED permanently: endpoint=${endpoint.id} " +
                    "url=${endpoint.url} event=${delivery.eventType} attempts=$newAttempts",
            )
        } else {
            log.debug(
                "Webhook delivered: endpoint=${endpoint.id} " +
                    "url=${endpoint.url} event=${delivery.eventType} status=$responseStatus",
            )
        }
    }

    /**
     * Performs one HTTP POST. Returns (httpStatus, isSuccess).
     * A 2xx response code is considered success; anything else (including exceptions)
     * is a failure that triggers a retry.
     */
    private fun sendRequest(
        endpoint: WebhookEndpoint,
        payload: String,
    ): Pair<Int?, Boolean> =
        try {
            val url = URI(endpoint.url).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "KotAuth-Webhook/1.0")
            conn.setRequestProperty("X-KotAuth-Event", endpoint.url)
            conn.setRequestProperty("X-KotAuth-Signature", computeSignature(endpoint.secret, payload))

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }

            val status = conn.responseCode
            val success = status in 200..299
            Pair(status, success)
        } catch (e: Exception) {
            log.warn("Webhook HTTP error: endpoint=${endpoint.id} url=${endpoint.url}: ${e.message}")
            Pair(null, false)
        }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    /**
     * Builds the standard JSON payload envelope:
     * ```json
     * {
     *   "event": "user.created",
     *   "timestamp": "2026-03-17T12:00:00Z",
     *   "data": { ... }
     * }
     * ```
     * Using string concatenation avoids a JSON library dependency in the domain layer.
     */
    private fun buildPayload(
        eventType: String,
        data: Map<String, Any?>,
    ): String {
        val dataJson =
            data.entries.joinToString(",", "{", "}") { (k, v) ->
                val escaped = k.replace("\"", "\\\"")
                val value =
                    when (v) {
                        null -> "null"
                        is Boolean -> v.toString()
                        is Number -> v.toString()
                        else -> "\"${v.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    }
                "\"$escaped\":$value"
            }
        val ts = Instant.now().toString()
        return """{"event":"$eventType","timestamp":"$ts","data":$dataJson}"""
    }

    /**
     * Computes `sha256=<hex>` HMAC-SHA256 signature for the given [payload].
     * Receivers verify this against the shared secret using the same algorithm.
     */
    private fun computeSignature(
        secret: String,
        payload: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hex =
            mac
                .doFinal(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "sha256=$hex"
    }

    /** Generates a cryptographically random 32-byte hex secret. */
    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

// =============================================================================
// Result type
// =============================================================================

sealed class WebhookResult {
    data class Success(
        val endpoint: WebhookEndpoint,
        /** Plaintext secret returned once at creation. Not stored in plain form. */
        val plaintextSecret: String,
    ) : WebhookResult()

    data class Failure(
        val error: String,
    ) : WebhookResult()
}
