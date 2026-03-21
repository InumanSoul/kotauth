package com.kauth.fakes

import com.kauth.domain.model.ApiKey
import com.kauth.domain.port.ApiKeyRepository
import java.time.Instant

/**
 * In-memory ApiKeyRepository for unit tests.
 */
class FakeApiKeyRepository : ApiKeyRepository {
    private val store = mutableMapOf<Int, ApiKey>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<ApiKey> = store.values.toList()

    override fun save(apiKey: ApiKey): ApiKey {
        val k = if (apiKey.id == null) apiKey.copy(id = nextId++) else apiKey
        store[k.id!!] = k
        return k
    }

    override fun findByHash(hash: String): ApiKey? = store.values.find { it.keyHash == hash }

    override fun findByTenantId(tenantId: Int): List<ApiKey> =
        store.values.filter { it.tenantId == tenantId }.sortedByDescending { it.createdAt }

    override fun findById(
        id: Int,
        tenantId: Int,
    ): ApiKey? = store[id]?.takeIf { it.tenantId == tenantId }

    override fun revoke(
        id: Int,
        tenantId: Int,
    ) {
        store[id]?.takeIf { it.tenantId == tenantId }?.let {
            store[id] = it.copy(enabled = false)
        }
    }

    override fun touchLastUsed(
        id: Int,
        at: Instant,
    ) {
        store[id]?.let { store[id] = it.copy(lastUsedAt = at) }
    }

    override fun delete(
        id: Int,
        tenantId: Int,
    ) {
        if (store[id]?.tenantId == tenantId) {
            store.remove(id)
        }
    }
}
