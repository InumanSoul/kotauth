package com.kauth.adapter.persistence

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.port.AuditLogRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Persistence adapter — read side of the audit log.
 *
 * Query-only (no writes). All writes go through [PostgresAuditLogAdapter].
 */
class PostgresAuditLogRepository : AuditLogRepository {

    override fun findByTenant(
        tenantId: Int,
        eventType: AuditEventType?,
        userId: Int?,
        limit: Int,
        offset: Int
    ): List<AuditEvent> = transaction {
        val query = AuditLogTable.selectAll()
            .where { AuditLogTable.tenantId eq tenantId }
        if (eventType != null) query.andWhere { AuditLogTable.eventType eq eventType.name }
        if (userId != null)    query.andWhere { AuditLogTable.userId eq userId }
        query
            .orderBy(AuditLogTable.createdAt, SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map { it.toAuditEvent() }
    }

    override fun countByTenant(
        tenantId: Int,
        eventType: AuditEventType?,
        userId: Int?
    ): Long = transaction {
        val query = AuditLogTable.selectAll()
            .where { AuditLogTable.tenantId eq tenantId }
        if (eventType != null) query.andWhere { AuditLogTable.eventType eq eventType.name }
        if (userId != null)    query.andWhere { AuditLogTable.userId eq userId }
        query.count()
    }

    private fun ResultRow.toAuditEvent(): AuditEvent = AuditEvent(
        tenantId  = this[AuditLogTable.tenantId],
        userId    = this[AuditLogTable.userId],
        clientId  = this[AuditLogTable.clientId],
        eventType = runCatching { AuditEventType.valueOf(this[AuditLogTable.eventType]) }
                        .getOrDefault(AuditEventType.LOGIN_SUCCESS),
        ipAddress = this[AuditLogTable.ipAddress],
        userAgent = this[AuditLogTable.userAgent],
        details   = emptyMap(),   // details JSONB deserialization is out of scope for admin UI display
        createdAt = this[AuditLogTable.createdAt].toInstant()
    )
}
