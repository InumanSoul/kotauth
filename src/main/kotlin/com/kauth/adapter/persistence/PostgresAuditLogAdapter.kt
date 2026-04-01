package com.kauth.adapter.persistence

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.WebhookEventType
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.service.WebhookService
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter — audit log (append-only).
 *
 * All exceptions are caught and logged locally.
 * Audit failures MUST NOT bubble up to callers (see [AuditLogPort] contract).
 *
 * Optionally accepts a [WebhookService] and fans out webhook events
 * after each successful audit write. The fan-out is fire-and-forget — webhook
 * failures never affect the audit write or the calling auth flow.
 */
class PostgresAuditLogAdapter(
    private val webhookService: WebhookService? = null,
) : AuditLogPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun record(event: AuditEvent) {
        try {
            transaction {
                AuditLogTable.insert {
                    it[tenantId] = event.tenantId?.value
                    it[userId] = event.userId?.value
                    it[clientId] = event.clientId?.value
                    it[eventType] = event.eventType.name
                    it[ipAddress] = event.ipAddress
                    it[userAgent] = event.userAgent
                    it[details] =
                        if (event.details.isEmpty()) {
                            null
                        } else {
                            event.details.entries.joinToString(",", "{", "}") { (k, v) ->
                                "\"$k\":\"${v.replace("\"", "\\\"")}\""
                            }
                        }
                    it[createdAt] = OffsetDateTime.ofInstant(event.createdAt, ZoneOffset.UTC)
                }
            }
        } catch (e: Exception) {
            log.error("Audit log write failed for event ${event.eventType}: ${e.message}", e)
            // Intentionally swallowed — audit failure must not disrupt auth flow
        }

        // Dispatch webhook event (fire-and-forget; exceptions never propagate)
        val tenantId = event.tenantId ?: return
        val webhookEventType = auditTypeToWebhookEvent(event.eventType) ?: return
        try {
            webhookService?.dispatch(
                tenantId = tenantId,
                eventType = webhookEventType,
                payloadData = buildPayloadData(event),
            )
        } catch (e: Exception) {
            log.error("Webhook dispatch failed for event ${event.eventType}: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------------------
    // Webhook event mapping
    // -------------------------------------------------------------------------

    /** Maps [AuditEventType] values to their webhook event string counterparts. */
    private fun auditTypeToWebhookEvent(type: AuditEventType): WebhookEventType? =
        when (type) {
            AuditEventType.ADMIN_USER_CREATED,
            AuditEventType.REGISTER_SUCCESS,
            -> WebhookEventType.USER_CREATED

            AuditEventType.ADMIN_USER_UPDATED,
            AuditEventType.USER_PROFILE_UPDATED,
            -> WebhookEventType.USER_UPDATED

            AuditEventType.ADMIN_USER_DISABLED,
            AuditEventType.ADMIN_USER_ENABLED,
            -> WebhookEventType.USER_UPDATED

            AuditEventType.LOGIN_SUCCESS -> WebhookEventType.LOGIN_SUCCESS
            AuditEventType.LOGIN_FAILED -> WebhookEventType.LOGIN_FAILED

            AuditEventType.PASSWORD_RESET_COMPLETED,
            AuditEventType.ADMIN_USER_PASSWORD_RESET,
            -> WebhookEventType.PASSWORD_RESET

            AuditEventType.MFA_ENROLLMENT_VERIFIED -> WebhookEventType.MFA_ENROLLED

            AuditEventType.SESSION_REVOKED,
            AuditEventType.ADMIN_SESSION_REVOKED,
            AuditEventType.USER_SESSION_REVOKED_SELF,
            -> WebhookEventType.SESSION_REVOKED

            else -> null
        }

    /** Extracts a flat map of useful fields from an [AuditEvent] for the webhook payload. */
    private fun buildPayloadData(event: AuditEvent): Map<String, Any?> =
        buildMap {
            event.userId?.let { put("userId", it.value) }
            event.clientId?.let { put("clientId", it.value) }
            event.ipAddress?.let { put("ipAddress", it) }
            putAll(event.details)
        }
}
