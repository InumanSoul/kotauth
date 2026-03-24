package com.kauth.domain.port

import com.kauth.domain.model.PortalConfig
import com.kauth.domain.model.TenantId

/**
 * Port (outbound) — persistence for portal UI configuration.
 */
interface PortalConfigRepository {
    fun findByTenantId(tenantId: TenantId): PortalConfig?

    fun upsert(tenantId: TenantId, config: PortalConfig): PortalConfig
}
