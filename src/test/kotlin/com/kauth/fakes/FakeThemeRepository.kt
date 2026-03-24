package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.ThemeRepository

/**
 * In-memory ThemeRepository for unit tests.
 */
class FakeThemeRepository : ThemeRepository {
    private val store = mutableMapOf<Int, TenantTheme>()

    override fun findByTenantId(tenantId: TenantId): TenantTheme? = store[tenantId.value]

    override fun upsert(
        tenantId: TenantId,
        theme: TenantTheme,
    ): TenantTheme {
        store[tenantId.value] = theme
        return theme
    }

    fun clear() {
        store.clear()
    }
}
