package com.kauth.domain.port

import com.kauth.domain.model.TenantEmailBranding
import com.kauth.domain.model.TenantId

interface TenantEmailBrandingRepository {
    fun findByTenantId(tenantId: TenantId): TenantEmailBranding?

    fun upsert(branding: TenantEmailBranding): TenantEmailBranding

    fun deleteByTenantId(tenantId: TenantId)
}
