package com.kauth.adapter.persistence

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.port.AuditLogPort
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
 */
class PostgresAuditLogAdapter : AuditLogPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun record(event: AuditEvent) {
        try {
            transaction {
                AuditLogTable.insert {
                    it[tenantId]  = event.tenantId
                    it[userId]    = event.userId
                    it[clientId]  = event.clientId
                    it[eventType] = event.eventType.name
                    it[ipAddress] = event.ipAddress
                    it[userAgent] = event.userAgent
                    it[details]   = if (event.details.isEmpty()) null
                                    else event.details.entries.joinToString(",", "{", "}") { (k, v) ->
                                        "\"$k\":\"${v.replace("\"", "\\\"")}\""
                                    }
                    it[createdAt] = OffsetDateTime.ofInstant(event.createdAt, ZoneOffset.UTC)
                }
            }
        } catch (e: Exception) {
            log.error("Audit log write failed for event ${event.eventType}: ${e.message}", e)
            // Intentionally swallowed — audit failure must not disrupt auth flow
        }
    }
}
