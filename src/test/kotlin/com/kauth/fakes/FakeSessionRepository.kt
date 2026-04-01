package com.kauth.fakes

import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import java.time.Instant

/**
 * In-memory SessionRepository for unit tests.
 * Supports all lifecycle operations: save, revoke, query, oldest-eviction.
 */
class FakeSessionRepository : SessionRepository {
    private val store = mutableMapOf<Int, Session>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<Session> = store.values.toList()

    override fun save(session: Session): Session {
        val s = session.copy(id = SessionId(nextId++))
        store[s.id!!.value] = s
        return s
    }

    override fun findActiveByAccessTokenHash(hash: String) =
        store.values.find { it.accessTokenHash == hash && it.isActive }

    override fun findActiveByRefreshTokenHash(hash: String) =
        store.values.find { it.refreshTokenHash == hash && it.isActive }

    override fun revoke(
        sessionId: SessionId,
        revokedAt: Instant,
    ) {
        store[sessionId.value]?.let { store[sessionId.value] = it.copy(revokedAt = revokedAt) }
    }

    override fun revokeAllForUser(
        tenantId: TenantId,
        userId: UserId,
        revokedAt: Instant,
    ) {
        store.values
            .filter { it.tenantId == tenantId && it.userId == userId && it.isActive }
            .forEach { store[it.id!!.value] = it.copy(revokedAt = revokedAt) }
    }

    override fun revokeAllForTenant(
        tenantId: TenantId,
        revokedAt: Instant,
    ): Int {
        val active = store.values.filter { it.tenantId == tenantId && it.isActive }
        active.forEach { store[it.id!!.value] = it.copy(revokedAt = revokedAt) }
        return active.size
    }

    override fun findActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ) = store.values.filter { it.tenantId == tenantId && it.userId == userId && it.isActive }

    override fun findById(id: SessionId) = store[id.value]

    override fun findActiveByTenant(tenantId: TenantId) = store.values.filter { it.tenantId == tenantId && it.isActive }

    override fun countActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ) = store.values.count { it.tenantId == tenantId && it.userId == userId && it.isActive }

    override fun revokeOldestForUser(
        tenantId: TenantId,
        userId: UserId,
        keepNewest: Int,
    ) {
        val active =
            store.values
                .filter { it.tenantId == tenantId && it.userId == userId && it.isActive }
                .sortedBy { it.createdAt }
        active.dropLast(keepNewest).forEach { revoke(it.id!!, Instant.now()) }
    }

    override fun deleteExpired(retentionDays: Int): Int {
        val cutoff = Instant.now().minusSeconds(retentionDays * 86400L)
        val toDelete = store.values.filter { it.expiresAt.isBefore(cutoff) || (it.revokedAt?.isBefore(cutoff) == true) }
        toDelete.forEach { store.remove(it.id?.value) }
        return toDelete.size
    }
}
