package com.kauth.domain.port

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType

/**
 * Port (outbound) — read side of the audit log.
 *
 * Separate from [AuditLogPort] (write-only, fire-and-forget) to maintain
 * clean separation between the append-only auth flow path and the
 * admin console query path.
 */
interface AuditLogRepository {
    /**
     * Returns recent audit events for a tenant, ordered by [createdAt] DESC.
     *
     * @param tenantId  the tenant to query
     * @param eventType optional filter by event type
     * @param userId    optional filter by user
     * @param limit     max rows to return (default 50)
     * @param offset    pagination offset
     */
    fun findByTenant(
        tenantId: Int,
        eventType: AuditEventType? = null,
        userId: Int? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<AuditEvent>

    /** Returns the total count for the given filters (for pagination UI). */
    fun countByTenant(
        tenantId: Int,
        eventType: AuditEventType? = null,
        userId: Int? = null,
    ): Long
}
