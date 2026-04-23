package com.kauth.fakes

import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.TenantClaimMapperRepository

/**
 * In-memory TenantClaimMapperRepository for unit tests.
 * Keyed by (tenantId, attributeKey) to mirror the PK of the real table.
 */
class FakeTenantClaimMapperRepository : TenantClaimMapperRepository {
    private val store = mutableMapOf<Pair<Int, String>, TenantClaimMapper>()

    fun clear() {
        store.clear()
    }

    override fun findAll(tenantId: TenantId): List<TenantClaimMapper> =
        store.values
            .filter { it.tenantId == tenantId }
            .sortedBy { it.attributeKey }

    override fun upsert(mapper: TenantClaimMapper) {
        store[mapper.tenantId.value to mapper.attributeKey] = mapper
    }

    override fun delete(
        tenantId: TenantId,
        attributeKey: String,
    ): Boolean {
        val key = tenantId.value to attributeKey
        return store.remove(key) != null
    }
}
