package com.kauth.fakes

import com.kauth.domain.model.TenantEmailBranding
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.TenantEmailBrandingRepository

class FakeTenantEmailBrandingRepository : TenantEmailBrandingRepository {
    private val store = mutableMapOf<TenantId, TenantEmailBranding>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    override fun findByTenantId(tenantId: TenantId): TenantEmailBranding? = store[tenantId]

    override fun upsert(branding: TenantEmailBranding): TenantEmailBranding {
        val existing = store[branding.tenantId]
        val saved = branding.copy(id = existing?.id ?: nextId++)
        store[saved.tenantId] = saved
        return saved
    }

    override fun deleteByTenantId(tenantId: TenantId) {
        store.remove(tenantId)
    }
}
