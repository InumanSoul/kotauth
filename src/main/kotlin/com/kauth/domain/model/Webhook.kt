package com.kauth.domain.model

import java.time.Instant

// =============================================================================
// Webhook domain models
// =============================================================================

/**
 * A tenant-configured HTTP endpoint that receives POST callbacks when
 * subscribed events occur in KotAuth.
 *
 * The [secret] is stored as-is server-side and used as the HMAC-SHA256 key
 * when signing each payload (see [WebhookService]). Receivers verify the
 * X-KotAuth-Signature header to confirm authenticity.
 *
 * [events] is the set of [WebhookEvent] names this endpoint subscribes to.
 * An empty set means the endpoint receives nothing.
 */
data class WebhookEndpoint(
    val id: Int? = null,
    val tenantId: TenantId,
    val url: String,
    /** HMAC-SHA256 signing key — returned once at creation, never shown again in the UI. */
    val secret: String,
    val events: Set<String>,
    val description: String = "",
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

/**
 * A single delivery attempt record for one event dispatched to one endpoint.
 *
 * Status lifecycle: [WebhookDeliveryStatus.PENDING] → [WebhookDeliveryStatus.DELIVERED]
 * or [WebhookDeliveryStatus.FAILED] after exhausting retries.
 */
data class WebhookDelivery(
    val id: Int? = null,
    val endpointId: Int,
    /** Webhook event name, e.g. "user.created". */
    val eventType: String,
    /** Raw JSON string of the payload that was (or will be) sent. */
    val payload: String,
    val status: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptAt: Instant? = null,
    /** HTTP status code returned by the receiver, or null if no response was received. */
    val responseStatus: Int? = null,
    val createdAt: Instant = Instant.now(),
)

enum class WebhookDeliveryStatus(
    val value: String,
) {
    PENDING("pending"),
    DELIVERED("delivered"),
    FAILED("failed"),
    ;

    companion object {
        fun fromValue(v: String): WebhookDeliveryStatus =
            entries.firstOrNull { it.value == v }
                ?: throw IllegalArgumentException("Unknown webhook delivery status: $v")
    }
}

/**
 * Canonical webhook event names.
 *
 * These strings appear verbatim in the [WebhookEndpoint.events] set and in the
 * `event_type` field of [WebhookDelivery] records. They are intentionally
 * separate from [AuditEventType] — the audit log is internal; webhook events
 * are a public contract with integrators.
 */
object WebhookEvent {
    const val USER_CREATED = "user.created"
    const val USER_UPDATED = "user.updated"
    const val USER_DELETED = "user.deleted"
    const val LOGIN_SUCCESS = "login.success"
    const val LOGIN_FAILED = "login.failed"
    const val PASSWORD_RESET = "password.reset"
    const val MFA_ENROLLED = "mfa.enrolled"
    const val SESSION_REVOKED = "session.revoked"

    val ALL =
        listOf(
            USER_CREATED,
            USER_UPDATED,
            USER_DELETED,
            LOGIN_SUCCESS,
            LOGIN_FAILED,
            PASSWORD_RESET,
            MFA_ENROLLED,
            SESSION_REVOKED,
        )
}
