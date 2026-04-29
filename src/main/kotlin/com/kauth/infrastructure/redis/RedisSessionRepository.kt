package com.kauth.infrastructure.redis

import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import io.lettuce.core.api.sync.RedisCommands
import java.time.Instant

/**
 * Redis-backed session storage. Used when `KAUTH_REDIS_URL` is set;
 * [com.kauth.adapter.persistence.PostgresSessionRepository] is used otherwise.
 *
 * See [RedisKeys] for the keyspace layout. Per-record TTL is `max(expiresAt,
 * refreshExpiresAt) + retentionDays`; Redis expires records automatically so
 * [deleteExpired] is a no-op.
 *
 * Active-by-user and active-by-tenant indexes are sorted sets scored by
 * `createdAt` epoch-millis. They are kept in sync on save/revoke, and
 * opportunistically reconciled on read to drop members whose primary record
 * has been TTL'd out.
 */
class RedisSessionRepository(
    private val commands: RedisCommands<String, String>,
    private val retentionDays: Int = 7,
) : SessionRepository {
    override fun save(session: Session): Session {
        val id = SessionId(commands.incr(RedisKeys.ID_COUNTER).toInt())
        val saved = session.copy(id = id)

        commands.setex(RedisKeys.record(id), recordTtlSeconds(saved), SessionCodec.encode(saved))
        commands.setex(
            RedisKeys.accessTokenIndex(saved.accessTokenHash),
            secondsUntil(saved.expiresAt),
            id.value.toString(),
        )
        if (saved.refreshTokenHash != null && saved.refreshExpiresAt != null) {
            commands.setex(
                RedisKeys.refreshTokenIndex(saved.refreshTokenHash),
                secondsUntil(saved.refreshExpiresAt),
                id.value.toString(),
            )
        }

        val score = saved.createdAt.toEpochMilli().toDouble()
        saved.userId?.let { uid ->
            commands.zadd(RedisKeys.activeUserSet(saved.tenantId, uid), score, id.value.toString())
        }
        commands.zadd(RedisKeys.activeTenantSet(saved.tenantId), score, id.value.toString())

        return saved
    }

    override fun findActiveByAccessTokenHash(hash: String): Session? {
        val id = commands.get(RedisKeys.accessTokenIndex(hash))?.toIntOrNull() ?: return null
        return findById(SessionId(id))?.takeIf { it.isActive }
    }

    override fun findActiveByRefreshTokenHash(hash: String): Session? {
        val id = commands.get(RedisKeys.refreshTokenIndex(hash))?.toIntOrNull() ?: return null
        val session = findById(SessionId(id)) ?: return null
        if (session.isRevoked) return null
        val refreshExpires = session.refreshExpiresAt ?: return null
        return session.takeIf { Instant.now().isBefore(refreshExpires) }
    }

    override fun revoke(
        sessionId: SessionId,
        revokedAt: Instant,
    ) {
        val current = findById(sessionId) ?: return
        if (current.isRevoked) return
        val updated = current.copy(revokedAt = revokedAt)
        commands.setex(RedisKeys.record(sessionId), recordTtlSeconds(updated), SessionCodec.encode(updated))
        updated.userId?.let { uid ->
            commands.zrem(RedisKeys.activeUserSet(updated.tenantId, uid), sessionId.value.toString())
        }
        commands.zrem(RedisKeys.activeTenantSet(updated.tenantId), sessionId.value.toString())
    }

    override fun revokeAllForUser(
        tenantId: TenantId,
        userId: UserId,
        revokedAt: Instant,
    ) {
        commands.zrange(RedisKeys.activeUserSet(tenantId, userId), 0, -1).forEach {
            it.toIntOrNull()?.let { id -> revoke(SessionId(id), revokedAt) }
        }
    }

    override fun revokeAllForTenant(
        tenantId: TenantId,
        revokedAt: Instant,
    ): Int {
        val members = commands.zrange(RedisKeys.activeTenantSet(tenantId), 0, -1)
        members.forEach { it.toIntOrNull()?.let { id -> revoke(SessionId(id), revokedAt) } }
        return members.size
    }

    override fun findActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): List<Session> = liveSessions(RedisKeys.activeUserSet(tenantId, userId)).sortedByDescending { it.createdAt }

    override fun findById(id: SessionId): Session? = commands.get(RedisKeys.record(id))?.let(SessionCodec::decode)

    override fun findActiveByTenant(
        tenantId: TenantId,
        limit: Int,
        offset: Int,
    ): List<Session> =
        liveSessions(RedisKeys.activeTenantSet(tenantId))
            .sortedByDescending { it.createdAt }
            .drop(offset)
            .take(limit)

    override fun countActiveByTenant(tenantId: TenantId): Int = liveSessions(RedisKeys.activeTenantSet(tenantId)).size

    override fun countActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): Int = liveSessions(RedisKeys.activeUserSet(tenantId, userId)).size

    override fun revokeOldestForUser(
        tenantId: TenantId,
        userId: UserId,
        keepNewest: Int,
    ) {
        // ZRANGE returns ascending score order — oldest first.
        val key = RedisKeys.activeUserSet(tenantId, userId)
        val members = commands.zrange(key, 0, -1)
        val toRevoke = members.dropLast(keepNewest)
        val now = Instant.now()
        toRevoke.forEach { it.toIntOrNull()?.let { id -> revoke(SessionId(id), now) } }
    }

    /**
     * Redis TTL handles physical expiry; this is a no-op kept on the interface
     * so the background sweeper in `Application.kt` stays storage-agnostic.
     */
    override fun deleteExpired(retentionDays: Int): Int = 0

    /**
     * Reads members of an active-set, fetches their records, and returns the
     * sessions that are still live (record present, not revoked, not expired).
     * Stale members (record TTL'd out) are opportunistically removed from the
     * set so it doesn't accumulate orphans.
     */
    private fun liveSessions(setKey: String): List<Session> {
        val members = commands.zrange(setKey, 0, -1)
        if (members.isEmpty()) return emptyList()

        val live = mutableListOf<Session>()
        val toPrune = mutableListOf<String>()
        for (member in members) {
            val id = member.toIntOrNull()
            if (id == null) {
                toPrune += member
                continue
            }
            val session = findById(SessionId(id))
            when {
                session == null -> toPrune += member
                !session.isActive -> toPrune += member
                else -> live += session
            }
        }
        if (toPrune.isNotEmpty()) {
            commands.zrem(setKey, *toPrune.toTypedArray())
        }
        return live
    }

    private fun recordTtlSeconds(session: Session): Long {
        val maxExpiry = listOfNotNull(session.expiresAt, session.refreshExpiresAt).max()
        return secondsUntil(maxExpiry) + retentionDays.toLong() * 86_400L
    }

    private fun secondsUntil(target: Instant): Long = (target.epochSecond - Instant.now().epochSecond).coerceAtLeast(1L)
}
