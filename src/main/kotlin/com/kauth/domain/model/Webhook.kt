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
    val events: Set<WebhookEventType>,
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
    val eventType: WebhookEventType,
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
 * Typed webhook event names — public contract with integrators.
 * Intentionally separate from [AuditEventType] (audit is internal).
 */
enum class WebhookEventType(
    val value: String,
) {
    USER_CREATED("user.created"),
    USER_UPDATED("user.updated"),
    USER_DELETED("user.deleted"),
    LOGIN_SUCCESS("login.success"),
    LOGIN_FAILED("login.failed"),
    PASSWORD_RESET("password.reset"),
    MFA_ENROLLED("mfa.enrolled"),
    SESSION_REVOKED("session.revoked"),
    ;

    companion object {
        fun fromValue(v: String): WebhookEventType? = entries.find { it.value == v }
    }
}
