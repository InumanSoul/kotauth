package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.domain.port.WebAuthnCredentialRepository
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class FakeWebAuthnCredentialRepository : WebAuthnCredentialRepository {
    private val store = mutableMapOf<Long, WebAuthnCredential>()
    private val nextId = AtomicLong(1)

    fun clear() {
        store.clear()
        nextId.set(1)
    }

    override fun save(credential: WebAuthnCredential): WebAuthnCredential {
        val assigned = credential.id ?: nextId.getAndIncrement()
        val stored = credential.copy(id = assigned)
        store[assigned] = stored
        return stored
    }

    override fun findById(id: Long): WebAuthnCredential? = store[id]

    override fun findByCredentialId(credentialId: String): WebAuthnCredential? =
        store.values.firstOrNull { it.credentialId == credentialId }

    override fun findByUserId(
        userId: UserId,
        tenantId: TenantId,
    ): List<WebAuthnCredential> =
        store.values
            .filter { it.userId == userId && it.tenantId == tenantId }
            .sortedBy { it.createdAt }

    override fun updateCounter(
        id: Long,
        signCounter: Long,
        lastUsedAt: Instant,
    ) {
        store[id]?.let {
            store[id] = it.copy(signCounter = signCounter, lastUsedAt = lastUsedAt)
        }
    }

    override fun rename(
        id: Long,
        userId: UserId,
        newName: String,
    ): Boolean {
        val existing = store[id] ?: return false
        if (existing.userId != userId) return false
        store[id] = existing.copy(name = newName)
        return true
    }

    override fun delete(
        id: Long,
        userId: UserId,
    ): Boolean {
        val existing = store[id] ?: return false
        if (existing.userId != userId) return false
        store.remove(id)
        return true
    }

    override fun deleteAllByUserId(
        userId: UserId,
        tenantId: TenantId,
    ): Int {
        val toRemove = store.values.filter { it.userId == userId && it.tenantId == tenantId }.map { it.id!! }
        toRemove.forEach { store.remove(it) }
        return toRemove.size
    }
}
