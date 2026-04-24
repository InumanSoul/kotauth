package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserAttributeRepository

/**
 * In-memory UserAttributeRepository for unit tests.
 * Keyed by (userId, key) to mirror the PK of the real table.
 */
class FakeUserAttributeRepository : UserAttributeRepository {
    private val store = mutableMapOf<Pair<Int, String>, UserAttribute>()

    fun clear() {
        store.clear()
    }

    fun all(): List<UserAttribute> = store.values.toList()

    override fun findAll(
        userId: UserId,
        tenantId: TenantId,
    ): Map<String, String> =
        store.values
            .filter { it.userId == userId && it.tenantId == tenantId }
            .associate { it.key to it.value }

    override fun upsert(attribute: UserAttribute) {
        store[attribute.userId.value to attribute.key] = attribute
    }

    override fun delete(
        userId: UserId,
        tenantId: TenantId,
        key: String,
    ): Boolean {
        val existing = store[userId.value to key] ?: return false
        if (existing.tenantId != tenantId) return false
        store.remove(userId.value to key)
        return true
    }

    override fun deleteAllForUser(
        userId: UserId,
        tenantId: TenantId,
    ) {
        store.entries.removeIf { (_, attr) -> attr.userId == userId && attr.tenantId == tenantId }
    }
}
