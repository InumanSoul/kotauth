package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey
import com.kauth.domain.port.TenantKeyRepository

/**
 * In-memory TenantKeyRepository for unit tests.
 */
class FakeTenantKeyRepository : TenantKeyRepository {
    private val store = mutableListOf<TenantKey>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<TenantKey> = store.toList()

    fun add(key: TenantKey): TenantKey {
        val k = if (key.id == null) key.copy(id = nextId++) else key
        store.add(k)
        return k
    }

    override fun findActiveKey(tenantId: TenantId): TenantKey? =
        store.find { it.tenantId == tenantId && it.active && it.enabled }

    override fun findEnabledKeys(tenantId: TenantId): List<TenantKey> =
        store.filter { it.tenantId == tenantId && it.enabled }

    override fun findAllKeys(tenantId: TenantId): List<TenantKey> = store.filter { it.tenantId == tenantId }

    override fun findByKeyId(
        tenantId: TenantId,
        keyId: String,
    ): TenantKey? = store.find { it.tenantId == tenantId && it.keyId == keyId }

    override fun save(key: TenantKey): TenantKey {
        val k = key.copy(id = nextId++)
        store.add(k)
        return k
    }

    override fun rotate(
        tenantId: TenantId,
        newKeyId: String,
        previousKeyId: String,
    ) {
        val oldIdx = store.indexOfFirst { it.tenantId == tenantId && it.keyId == previousKeyId }
        if (oldIdx >= 0) store[oldIdx] = store[oldIdx].copy(active = false)
        val newIdx = store.indexOfFirst { it.tenantId == tenantId && it.keyId == newKeyId }
        if (newIdx >= 0) store[newIdx] = store[newIdx].copy(active = true)
    }

    override fun disable(
        tenantId: TenantId,
        keyId: String,
    ) {
        val idx = store.indexOfFirst { it.tenantId == tenantId && it.keyId == keyId }
        if (idx >= 0) store[idx] = store[idx].copy(enabled = false, active = false)
    }
}
