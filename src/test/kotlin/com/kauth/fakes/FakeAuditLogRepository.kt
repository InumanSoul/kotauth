package com.kauth.fakes

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogRepository

/**
 * In-memory AuditLogRepository for integration tests.
 * Implements the read-side port (findByTenant, countByTenant).
 */
class FakeAuditLogRepository : AuditLogRepository {
    private val store = mutableListOf<AuditEvent>()

    fun add(event: AuditEvent) {
        store.add(event)
    }

    fun clear() {
        store.clear()
    }

    override fun findByTenant(
        tenantId: TenantId,
        eventType: AuditEventType?,
        userId: UserId?,
        limit: Int,
        offset: Int,
    ): List<AuditEvent> =
        store
            .filter { it.tenantId == tenantId }
            .let { list -> if (eventType != null) list.filter { it.eventType == eventType } else list }
            .let { list -> if (userId != null) list.filter { it.userId == userId } else list }
            .sortedByDescending { it.createdAt }
            .drop(offset)
            .take(limit)

    override fun countByTenant(
        tenantId: TenantId,
        eventType: AuditEventType?,
        userId: UserId?,
    ): Long =
        store
            .filter { it.tenantId == tenantId }
            .let { list -> if (eventType != null) list.filter { it.eventType == eventType } else list }
            .let { list -> if (userId != null) list.filter { it.userId == userId } else list }
            .size
            .toLong()
}
