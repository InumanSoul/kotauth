package com.kauth.fakes

import com.kauth.domain.model.Session
import com.kauth.domain.port.SessionRepository
import java.time.Instant

/**
 * In-memory SessionRepository for unit tests.
 * Supports all lifecycle operations: save, revoke, query, oldest-eviction.
 */
class FakeSessionRepository : SessionRepository {

    private val store = mutableMapOf<Int, Session>()
    private var nextId = 1

    fun clear() { store.clear(); nextId = 1 }

    fun all(): List<Session> = store.values.toList()

    override fun save(session: Session): Session {
        val s = session.copy(id = nextId++)
        store[s.id!!] = s
        return s
    }

    override fun findActiveByAccessTokenHash(hash: String) =
        store.values.find { it.accessTokenHash == hash && it.isActive }

    override fun findActiveByRefreshTokenHash(hash: String) =
        store.values.find { it.refreshTokenHash == hash && it.isActive }

    override fun revoke(sessionId: Int, revokedAt: Instant) {
        store[sessionId]?.let { store[sessionId] = it.copy(revokedAt = revokedAt) }
    }

    override fun revokeAllForUser(tenantId: Int, userId: Int, revokedAt: Instant) {
        store.values
            .filter { it.tenantId == tenantId && it.userId == userId && it.isActive }
            .forEach { store[it.id!!] = it.copy(revokedAt = revokedAt) }
    }

    override fun findActiveByUser(tenantId: Int, userId: Int) =
        store.values.filter { it.tenantId == tenantId && it.userId == userId && it.isActive }

    override fun findById(id: Int) = store[id]

    override fun findActiveByTenant(tenantId: Int) =
        store.values.filter { it.tenantId == tenantId && it.isActive }

    override fun countActiveByUser(tenantId: Int, userId: Int) =
        store.values.count { it.tenantId == tenantId && it.userId == userId && it.isActive }

    override fun revokeOldestForUser(tenantId: Int, userId: Int, keepNewest: Int) {
        val active = store.values
            .filter { it.tenantId == tenantId && it.userId == userId && it.isActive }
            .sortedBy { it.createdAt }
        active.dropLast(keepNewest).forEach { revoke(it.id!!) }
    }
}
