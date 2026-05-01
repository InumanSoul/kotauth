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
 * Per-record TTL is `max(expiresAt, refreshExpiresAt) + retentionDays`, so
 * Redis evicts expired records automatically and [deleteExpired] is a no-op.
 *
 * The active-by-user and active-by-tenant ZSETs are kept in sync on save and
 * revoke. They are also opportunistically reconciled on read — when a record
 * has TTL'd out but the ZSET member remains, [liveSessions] drops it via
 * `ZREM` so the indexes don't accumulate orphans over time.
 *
 * @see RedisKeys for the keyspace layout.
 */
class RedisSessionRepository(
    private val commands: RedisCommands<String, String>,
    private val retentionDays: Int = 7,
) : SessionRepository {
    override fun save(session: Session): Session {
        val id = SessionId(commands.incr(RedisKeys.ID_COUNTER).toInt())
        val saved = session.copy(id = id)
        val nowEpoch = Instant.now().epochSecond

        commands.setex(RedisKeys.record(id), recordTtlSeconds(saved, nowEpoch), SessionCodec.encode(saved))
        commands.setex(
            RedisKeys.accessTokenIndex(saved.accessTokenHash),
            ttlSeconds(saved.expiresAt, nowEpoch),
            id.value.toString(),
        )
        if (saved.refreshTokenHash != null && saved.refreshExpiresAt != null) {
            commands.setex(
                RedisKeys.refreshTokenIndex(saved.refreshTokenHash),
                ttlSeconds(saved.refreshExpiresAt, nowEpoch),
                id.value.toString(),
            )
        }

        val score = saved.createdAt.toEpochMilli().toDouble()
        saved.userId?.let { uid ->
            commands.zadd(RedisKeys.activeUserSet(saved.tenantId, uid), score, id.value.toString())
        }
        commands.zadd(RedisKeys.activeTenantSet(saved.tenantId), score, id.value.toString())
        saved.impersonatorSessionId?.let { parentId ->
            commands.zadd(RedisKeys.impersonatorSet(parentId), score, id.value.toString())
        }

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
        revokeAndReport(sessionId, revokedAt)
        revokeAllByImpersonator(sessionId, revokedAt)
    }

    override fun revokeAllForUser(
        tenantId: TenantId,
        userId: UserId,
        revokedAt: Instant,
    ) {
        commands.zrange(RedisKeys.activeUserSet(tenantId, userId), 0, -1).forEach {
            it.toIntOrNull()?.let { id ->
                revokeAndReport(SessionId(id), revokedAt)
                revokeAllByImpersonator(SessionId(id), revokedAt)
            }
        }
    }

    override fun revokeAllForTenant(
        tenantId: TenantId,
        revokedAt: Instant,
    ): Int =
        commands
            .zrange(RedisKeys.activeTenantSet(tenantId), 0, -1)
            .mapNotNull { it.toIntOrNull() }
            .count { revokeAndReport(SessionId(it), revokedAt) }

    /**
     * Returns true when a live session was revoked, false when the id either
     * has no record (orphan ZSET member) or is already revoked. Callers that
     * report a count to the UI / audit log must use this instead of the raw
     * ZSET cardinality.
     */
    private fun revokeAndReport(
        sessionId: SessionId,
        revokedAt: Instant,
    ): Boolean {
        val current = findById(sessionId) ?: return false
        if (current.isRevoked) return false
        val updated = current.copy(revokedAt = revokedAt)
        val nowEpoch = Instant.now().epochSecond
        commands.setex(
            RedisKeys.record(sessionId),
            recordTtlSeconds(updated, nowEpoch),
            SessionCodec.encode(updated),
        )
        updated.userId?.let { uid ->
            commands.zrem(RedisKeys.activeUserSet(updated.tenantId, uid), sessionId.value.toString())
        }
        commands.zrem(RedisKeys.activeTenantSet(updated.tenantId), sessionId.value.toString())
        return true
    }

    override fun findActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): List<Session> = liveSessions(RedisKeys.activeUserSet(tenantId, userId), newestFirst = true)

    override fun findById(id: SessionId): Session? = commands.get(RedisKeys.record(id))?.let(SessionCodec::decode)

    override fun findActiveByTenant(
        tenantId: TenantId,
        limit: Int,
        offset: Int,
    ): List<Session> =
        liveSessions(RedisKeys.activeTenantSet(tenantId), newestFirst = true)
            .drop(offset)
            .take(limit)

    override fun countActiveByTenant(tenantId: TenantId): Int =
        liveSessions(RedisKeys.activeTenantSet(tenantId), newestFirst = false).size

    override fun countActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): Int = liveSessions(RedisKeys.activeUserSet(tenantId, userId), newestFirst = false).size

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

    override fun findActiveByImpersonator(parentSessionId: SessionId): List<Session> =
        liveSessions(RedisKeys.impersonatorSet(parentSessionId), newestFirst = true)

    override fun revokeAllByImpersonator(
        parentSessionId: SessionId,
        revokedAt: Instant,
    ): Int =
        commands
            .zrange(RedisKeys.impersonatorSet(parentSessionId), 0, -1)
            .mapNotNull { it.toIntOrNull() }
            .count { revokeAndReport(SessionId(it), revokedAt) }

    /**
     * Reads members of an active-set, batch-MGETs the records, and returns the
     * sessions that are still live (record present, not revoked, not expired).
     * Stale members (record TTL'd out) are opportunistically removed from the
     * set so it doesn't accumulate orphans.
     *
     * Round trips: 1 ZRANGE + 1 MGET (+ 1 ZREM only if orphans were found).
     */
    private fun liveSessions(
        setKey: String,
        newestFirst: Boolean,
    ): List<Session> {
        val members =
            if (newestFirst) commands.zrevrange(setKey, 0, -1) else commands.zrange(setKey, 0, -1)
        if (members.isEmpty()) return emptyList()

        val keyed = members.map { member -> member to member.toIntOrNull() }
        val recordKeys = keyed.mapNotNull { (_, id) -> id?.let { RedisKeys.record(SessionId(it)) } }

        val records: Map<String, Session> =
            if (recordKeys.isEmpty()) {
                emptyMap()
            } else {
                commands
                    .mget(*recordKeys.toTypedArray())
                    .filter { it.hasValue() }
                    .associate { it.key to SessionCodec.decode(it.value) }
            }

        val live = mutableListOf<Session>()
        val toPrune = mutableListOf<String>()
        for ((member, id) in keyed) {
            if (id == null) {
                toPrune += member
                continue
            }
            val session = records[RedisKeys.record(SessionId(id))]
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

    private fun recordTtlSeconds(
        session: Session,
        nowEpoch: Long,
    ): Long {
        val maxExpiry = listOfNotNull(session.expiresAt, session.refreshExpiresAt).max()
        return ttlSeconds(maxExpiry, nowEpoch) + retentionDays.toLong() * 86_400L
    }

    private fun ttlSeconds(
        target: Instant,
        nowEpoch: Long,
    ): Long = (target.epochSecond - nowEpoch).coerceAtLeast(1L)
}
