package com.kauth.domain.port

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme

/**
 * Port (outbound) — persistence for workspace visual theme configuration.
 */
interface ThemeRepository {
    fun findByTenantId(tenantId: TenantId): TenantTheme?

    fun upsert(
        tenantId: TenantId,
        theme: TenantTheme,
    ): TenantTheme
}
