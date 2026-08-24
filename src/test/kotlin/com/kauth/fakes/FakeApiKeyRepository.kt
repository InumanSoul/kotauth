package com.kauth.fakes

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.TenantId
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
        require(apiKey.keyPrefix.length <= 16) {
            "keyPrefix '${apiKey.keyPrefix}' (${apiKey.keyPrefix.length}) exceeds varchar(16)"
        }
        val k = if (apiKey.id == null) apiKey.copy(id = nextId++) else apiKey
        store[k.id!!] = k
        return k
    }

    override fun findByHash(hash: String): ApiKey? = store.values.find { it.keyHash == hash }

    override fun findByTenantId(tenantId: TenantId): List<ApiKey> =
        store.values.filter { it.tenantId == tenantId }.sortedByDescending { it.createdAt }

    override fun findById(
        id: Int,
        tenantId: TenantId,
    ): ApiKey? = store[id]?.takeIf { it.tenantId == tenantId }

    override fun revoke(
        id: Int,
        tenantId: TenantId,
    ) {
        store[id]?.takeIf { it.tenantId == tenantId }?.let {
            store[id] = it.copy(enabled = false)
        }
    }

    override fun updateScimDialect(
        id: Int,
        tenantId: TenantId,
        scimDialect: String,
    ) {
        store[id]?.takeIf { it.tenantId == tenantId }?.let {
            store[id] = it.copy(scimDialect = scimDialect)
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
        tenantId: TenantId,
    ) {
        if (store[id]?.tenantId == tenantId) {
            store.remove(id)
        }
    }

    override fun findByTenantAndName(
        tenantId: TenantId,
        name: String,
    ): ApiKey? = store.values.find { it.tenantId == tenantId && it.name == name }

    override fun updateBootstrap(
        id: Int,
        keyHash: String,
        scopes: List<String>,
        bootstrapName: String,
        scimDialect: String,
    ) {
        store[id]?.let {
            store[id] =
                it.copy(
                    keyHash = keyHash,
                    scopes = scopes,
                    enabled = true,
                    bootstrapName = bootstrapName,
                    scimDialect = scimDialect,
                )
        }
    }
}
