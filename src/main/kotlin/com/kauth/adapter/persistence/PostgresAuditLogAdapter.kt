package com.kauth.adapter.persistence

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.WebhookEventType
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.service.WebhookService
import com.kauth.infrastructure.AuditChainHasher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TreeMap

/**
 * Persistence adapter — audit log (append-only).
 *
 * All exceptions are caught and logged locally.
 * Audit failures MUST NOT bubble up to callers (see [AuditLogPort] contract).
 *
 * Optionally accepts a [WebhookService] and fans out webhook events
 * after each successful audit write. The fan-out is fire-and-forget — webhook
 * failures never affect the audit write or the calling auth flow.
 *
 * When [auditChainHasher] is provided each row is signed with HMAC-SHA256 and
 * linked to the previous row's hash, forming a tamper-evident chain per tenant.
 */
class PostgresAuditLogAdapter(
    private val webhookService: WebhookService? = null,
    private val auditChainHasher: AuditChainHasher? = null,
) : AuditLogPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun record(event: AuditEvent) {
        try {
            transaction {
                if (auditChainHasher != null) {
                    recordWithChain(event, auditChainHasher)
                } else {
                    recordSimple(event)
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
    // Insert strategies
    // -------------------------------------------------------------------------

    private fun recordSimple(event: AuditEvent) {
        AuditLogTable.insert {
            it[tenantId] = event.tenantId?.value
            it[userId] = event.userId?.value
            it[clientId] = event.clientId?.value
            it[eventType] = event.eventType.name
            it[ipAddress] = event.ipAddress
            it[userAgent] = event.userAgent
            it[details] = serializeDetails(event.details)
            it[createdAt] = OffsetDateTime.ofInstant(event.createdAt, ZoneOffset.UTC)
        }
    }

    private fun recordWithChain(
        event: AuditEvent,
        hasher: AuditChainHasher,
    ) {
        // Serialize advisory lock token: per-tenant or sentinel 0 for system rows
        val lockToken =
            if (event.tenantId == null) {
                "0"
            } else {
                "hashtext('kauth/audit/' || ${event.tenantId.value})"
            }
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement("SELECT pg_advisory_xact_lock($lockToken)").execute()

        // Read previous row hash for this tenant
        val prevRow =
            AuditLogTable
                .select(AuditLogTable.id, AuditLogTable.rowHash, AuditLogTable.chainKeyId)
                .where {
                    if (event.tenantId == null) {
                        AuditLogTable.tenantId.isNull()
                    } else {
                        AuditLogTable.tenantId eq event.tenantId.value
                    }
                }.orderBy(AuditLogTable.id, SortOrder.DESC)
                .limit(1)
                .firstOrNull()

        val prevHash = prevRow?.get(AuditLogTable.rowHash)
        val detailsJson = serializeDetails(event.details)
        val createdAt = OffsetDateTime.ofInstant(event.createdAt, ZoneOffset.UTC)

        val inserted =
            AuditLogTable.insert {
                it[tenantId] = event.tenantId?.value
                it[userId] = event.userId?.value
                it[clientId] = event.clientId?.value
                it[eventType] = event.eventType.name
                it[ipAddress] = event.ipAddress
                it[userAgent] = event.userAgent
                it[details] = detailsJson
                it[AuditLogTable.createdAt] = createdAt
            }

        val insertedId = inserted[AuditLogTable.id]
        val insertedCreatedAt = inserted[AuditLogTable.createdAt]

        val canonical =
            hasher.canonicalize(
                id = insertedId,
                tenantId = event.tenantId?.value,
                userId = event.userId?.value,
                clientId = event.clientId?.value,
                eventType = event.eventType.name,
                ipAddress = event.ipAddress,
                userAgent = event.userAgent,
                createdAt = insertedCreatedAt,
                detailsJson = detailsJson,
                prevHash = prevHash,
            )
        val rowHash = hasher.hmac(canonical)

        AuditLogTable.update({ AuditLogTable.id eq insertedId }) {
            it[AuditLogTable.prevHash] = prevHash
            it[AuditLogTable.rowHash] = rowHash
            it[chainKeyId] = hasher.chainKeyId
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun serializeDetails(details: Map<String, String>): String? =
        if (details.isEmpty()) {
            null
        } else {
            Json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    TreeMap(details).forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                },
            )
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
