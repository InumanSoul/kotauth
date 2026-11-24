package com.kauth.fakes

import com.kauth.domain.model.ApplicationId
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

    /** What `ON DELETE SET NULL` does to a hard-deleted client's sessions. */
    fun detachClient(clientId: ApplicationId) {
        store.replaceAll { _, s -> if (s.clientId == clientId) s.copy(clientId = null) else s }
    }

    fun findByUserId(userId: UserId): List<Session> = store.values.filter { it.userId == userId }

    override fun save(session: Session): Session {
        val s = session.copy(id = SessionId(nextId++))
        store[s.id!!.value] = s
        return s
    }

    override fun findActiveByAccessTokenHash(hash: String) =
        store.values.find { it.accessTokenHash == hash && it.isActive }

    override fun findActiveByRefreshTokenHash(hash: String) =
        store.values.find { it.refreshTokenHash == hash && it.isActive }

    override fun findByRefreshTokenHash(hash: String) = store.values.find { it.refreshTokenHash == hash }

    override fun revoke(
        sessionId: SessionId,
        revokedAt: Instant,
        reason: String?,
    ) {
        store[sessionId.value]?.let {
            store[sessionId.value] = it.copy(revokedAt = revokedAt, revocationReason = reason)
        }
        cascadeImpersonatorChildren(sessionId, revokedAt)
    }

    override fun revokeAllForUser(
        tenantId: TenantId,
        userId: UserId,
        revokedAt: Instant,
    ) {
        store.values
            .filter { it.tenantId == tenantId && it.userId == userId && it.isActive }
            .forEach {
                store[it.id!!.value] = it.copy(revokedAt = revokedAt)
                cascadeImpersonatorChildren(it.id, revokedAt)
            }
    }

    private fun cascadeImpersonatorChildren(
        parentSessionId: SessionId,
        revokedAt: Instant,
    ) {
        store.values
            .filter { it.impersonatorSessionId == parentSessionId && it.isActive }
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

    override fun findActiveByTenant(
        tenantId: TenantId,
        userId: UserId?,
        applicationId: ApplicationId?,
        limit: Int,
        offset: Int,
    ) = store.values
        .filter { it.tenantId == tenantId && it.isActive }
        .filter { userId == null || it.userId == userId }
        .filter { applicationId == null || it.clientId == applicationId }
        .drop(offset)
        .take(limit)

    override fun countActiveByTenant(
        tenantId: TenantId,
        userId: UserId?,
        applicationId: ApplicationId?,
    ) = store.values
        .filter { it.tenantId == tenantId && it.isActive }
        .filter { userId == null || it.userId == userId }
        .filter { applicationId == null || it.clientId == applicationId }
        .size

    override fun findUsableByUserAndClient(
        tenantId: TenantId,
        userId: UserId,
        clientId: ApplicationId,
    ) = store.values
        .filter { it.tenantId == tenantId && it.userId == userId && it.clientId == clientId && it.isUsable }
        .sortedBy { it.createdAt }

    override fun deleteExpired(retentionDays: Int): Int {
        val cutoff = Instant.now().minusSeconds(retentionDays * 86400L)
        val toDelete = store.values.filter { it.expiresAt.isBefore(cutoff) || (it.revokedAt?.isBefore(cutoff) == true) }
        toDelete.forEach { store.remove(it.id?.value) }
        return toDelete.size
    }

    override fun findActiveByImpersonator(parentSessionId: SessionId): List<Session> =
        store.values.filter { it.impersonatorSessionId == parentSessionId && it.isActive }

    override fun revokeAllByImpersonator(
        parentSessionId: SessionId,
        revokedAt: Instant,
    ): Int {
        val active = store.values.filter { it.impersonatorSessionId == parentSessionId && it.isActive }
        active.forEach { store[it.id!!.value] = it.copy(revokedAt = revokedAt) }
        return active.size
    }
}
