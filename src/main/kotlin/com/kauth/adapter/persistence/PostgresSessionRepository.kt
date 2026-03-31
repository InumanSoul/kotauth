package com.kauth.adapter.persistence

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter for user sessions.
 */
class PostgresSessionRepository : SessionRepository {
    override fun save(session: Session): Session =
        transaction {
            val insertedId =
                SessionsTable.insert {
                    it[tenantId] = session.tenantId.value
                    it[userId] = session.userId?.value
                    it[clientId] = session.clientId?.value
                    it[accessTokenHash] = session.accessTokenHash
                    it[refreshTokenHash] = session.refreshTokenHash
                    it[scopes] = session.scopes
                    it[ipAddress] = session.ipAddress
                    it[userAgent] = session.userAgent
                    it[createdAt] = session.createdAt.toOffsetDateTime()
                    it[expiresAt] = session.expiresAt.toOffsetDateTime()
                    it[refreshExpiresAt] = session.refreshExpiresAt?.toOffsetDateTime()
                    it[lastActivityAt] = session.lastActivityAt.toOffsetDateTime()
                    it[revokedAt] = session.revokedAt?.toOffsetDateTime()
                } get SessionsTable.id

            session.copy(id = SessionId(insertedId))
        }

    override fun findActiveByAccessTokenHash(hash: String): Session? =
        transaction {
            val now = OffsetDateTime.now()
            SessionsTable
                .selectAll()
                .where {
                    (SessionsTable.accessTokenHash eq hash) and
                        (SessionsTable.revokedAt.isNull()) and
                        (SessionsTable.expiresAt greater now)
                }.map { it.toSession() }
                .singleOrNull()
        }

    override fun findActiveByRefreshTokenHash(hash: String): Session? =
        transaction {
            val now = OffsetDateTime.now()
            SessionsTable
                .selectAll()
                .where {
                    (SessionsTable.refreshTokenHash eq hash) and
                        (SessionsTable.revokedAt.isNull()) and
                        (SessionsTable.refreshExpiresAt.isNotNull()) and
                        (SessionsTable.refreshExpiresAt greater now)
                }.map { it.toSession() }
                .singleOrNull()
        }

    override fun revoke(
        sessionId: SessionId,
        revokedAt: Instant,
    ) = transaction {
        SessionsTable.update({ SessionsTable.id eq sessionId.value }) {
            it[SessionsTable.revokedAt] = revokedAt.toOffsetDateTime()
        }
        Unit
    }

    override fun revokeAllForUser(
        tenantId: TenantId,
        userId: UserId,
        revokedAt: Instant,
    ) = transaction {
        val ts = revokedAt.toOffsetDateTime()
        SessionsTable.update({
            (SessionsTable.tenantId eq tenantId.value) and
                (SessionsTable.userId eq userId.value) and
                (SessionsTable.revokedAt.isNull())
        }) {
            it[SessionsTable.revokedAt] = ts
        }
        Unit
    }

    override fun revokeAllForTenant(
        tenantId: TenantId,
        revokedAt: Instant,
    ): Int =
        transaction {
            val ts = revokedAt.toOffsetDateTime()
            SessionsTable.update({
                (SessionsTable.tenantId eq tenantId.value) and
                    (SessionsTable.revokedAt.isNull())
            }) {
                it[SessionsTable.revokedAt] = ts
            }
        }

    override fun findActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): List<Session> =
        transaction {
            val now = OffsetDateTime.now()
            SessionsTable
                .selectAll()
                .where {
                    (SessionsTable.tenantId eq tenantId.value) and
                        (SessionsTable.userId eq userId.value) and
                        (SessionsTable.revokedAt.isNull()) and
                        (SessionsTable.expiresAt greater now)
                }.orderBy(SessionsTable.createdAt, SortOrder.DESC)
                .map { it.toSession() }
        }

    override fun findById(id: SessionId): Session? =
        transaction {
            SessionsTable
                .selectAll()
                .where { SessionsTable.id eq id.value }
                .map { it.toSession() }
                .singleOrNull()
        }

    override fun findActiveByTenant(tenantId: TenantId): List<Session> =
        transaction {
            val now = OffsetDateTime.now()
            SessionsTable
                .selectAll()
                .where {
                    (SessionsTable.tenantId eq tenantId.value) and
                        (SessionsTable.revokedAt.isNull()) and
                        (SessionsTable.expiresAt greater now)
                }.orderBy(SessionsTable.createdAt, SortOrder.DESC)
                .map { it.toSession() }
        }

    override fun countActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): Int =
        transaction {
            val now = OffsetDateTime.now()
            SessionsTable
                .selectAll()
                .where {
                    (SessionsTable.tenantId eq tenantId.value) and
                        (SessionsTable.userId eq userId.value) and
                        (SessionsTable.revokedAt.isNull()) and
                        (SessionsTable.expiresAt greater now)
                }.count()
                .toInt()
        }

    override fun revokeOldestForUser(
        tenantId: TenantId,
        userId: UserId,
        keepNewest: Int,
    ) = transaction {
        val now = OffsetDateTime.now()
        val ts = now

        // Find all active session IDs ordered oldest-first, skip the N newest
        val allActive =
            SessionsTable
                .select(SessionsTable.id)
                .where {
                    (SessionsTable.tenantId eq tenantId.value) and
                        (SessionsTable.userId eq userId.value) and
                        (SessionsTable.revokedAt.isNull()) and
                        (SessionsTable.expiresAt greater now)
                }.orderBy(SessionsTable.createdAt, SortOrder.ASC)
                .map { it[SessionsTable.id] }

        val toRevoke = allActive.dropLast(keepNewest)
        if (toRevoke.isNotEmpty()) {
            SessionsTable.update({
                (SessionsTable.id inList toRevoke) and
                    (SessionsTable.revokedAt.isNull())
            }) {
                it[SessionsTable.revokedAt] = ts
            }
        }
        Unit
    }

    override fun deleteExpired(retentionDays: Int): Int =
        transaction {
            val cutoff = OffsetDateTime.now().minusDays(retentionDays.toLong())
            val expiredIds =
                SessionsTable
                    .select(SessionsTable.id)
                    .where {
                        (SessionsTable.expiresAt less cutoff) or
                            ((SessionsTable.revokedAt.isNotNull()) and (SessionsTable.revokedAt less cutoff))
                    }.map { it[SessionsTable.id] }
            if (expiredIds.isEmpty()) return@transaction 0
            expiredIds.chunked(500).sumOf { batch ->
                SessionsTable.deleteWhere { Op.build { SessionsTable.id inList batch } }
            }
        }

    // -------------------------------------------------------------------------
    // Mappers
    // -------------------------------------------------------------------------

    private fun ResultRow.toSession() =
        Session(
            id = SessionId(this[SessionsTable.id]),
            tenantId = TenantId(this[SessionsTable.tenantId]),
            userId = this[SessionsTable.userId]?.let { UserId(it) },
            clientId = this[SessionsTable.clientId]?.let { ApplicationId(it) },
            accessTokenHash = this[SessionsTable.accessTokenHash],
            refreshTokenHash = this[SessionsTable.refreshTokenHash],
            scopes = this[SessionsTable.scopes],
            ipAddress = this[SessionsTable.ipAddress],
            userAgent = this[SessionsTable.userAgent],
            createdAt = this[SessionsTable.createdAt].toInstant(),
            expiresAt = this[SessionsTable.expiresAt].toInstant(),
            refreshExpiresAt = this[SessionsTable.refreshExpiresAt]?.toInstant(),
            lastActivityAt = this[SessionsTable.lastActivityAt].toInstant(),
            revokedAt = this[SessionsTable.revokedAt]?.toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
