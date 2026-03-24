package com.kauth.fakes

import com.kauth.domain.model.PortalConfig
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.PortalConfigRepository

/**
 * In-memory PortalConfigRepository for unit tests.
 */
class FakePortalConfigRepository : PortalConfigRepository {
    private val store = mutableMapOf<Int, PortalConfig>()

    override fun findByTenantId(tenantId: TenantId): PortalConfig? = store[tenantId.value]

    override fun upsert(
        tenantId: TenantId,
        config: PortalConfig,
    ): PortalConfig {
        store[tenantId.value] = config
        return config
    }

    fun clear() {
        store.clear()
    }
}
