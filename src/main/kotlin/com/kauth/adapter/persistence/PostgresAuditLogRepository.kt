package com.kauth.adapter.persistence

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
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
        tenantId: TenantId,
        eventType: AuditEventType?,
        userId: UserId?,
        limit: Int,
        offset: Int,
    ): List<AuditEvent> =
        transaction {
            val query =
                AuditLogTable
                    .selectAll()
                    .where { AuditLogTable.tenantId eq tenantId.value }
            if (eventType != null) query.andWhere { AuditLogTable.eventType eq eventType.name }
            if (userId != null) query.andWhere { AuditLogTable.userId eq userId.value }
            query
                .orderBy(AuditLogTable.createdAt, SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { it.toAuditEvent() }
        }

    override fun countByTenant(
        tenantId: TenantId,
        eventType: AuditEventType?,
        userId: UserId?,
    ): Long =
        transaction {
            val query =
                AuditLogTable
                    .selectAll()
                    .where { AuditLogTable.tenantId eq tenantId.value }
            if (eventType != null) query.andWhere { AuditLogTable.eventType eq eventType.name }
            if (userId != null) query.andWhere { AuditLogTable.userId eq userId.value }
            query.count()
        }

    private fun ResultRow.toAuditEvent(): AuditEvent =
        AuditEvent(
            tenantId = this[AuditLogTable.tenantId]?.let { TenantId(it) },
            userId = this[AuditLogTable.userId]?.let { UserId(it) },
            clientId = this[AuditLogTable.clientId]?.let { ApplicationId(it) },
            eventType =
                runCatching { AuditEventType.valueOf(this[AuditLogTable.eventType]) }
                    .getOrDefault(AuditEventType.LOGIN_SUCCESS),
            ipAddress = this[AuditLogTable.ipAddress],
            userAgent = this[AuditLogTable.userAgent],
            details = emptyMap(), // details JSONB deserialization is out of scope for admin UI display
            createdAt = this[AuditLogTable.createdAt].toInstant(),
        )
}
