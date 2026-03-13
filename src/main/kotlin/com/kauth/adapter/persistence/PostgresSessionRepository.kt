package com.kauth.adapter.persistence

import com.kauth.domain.model.Session
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

    override fun save(session: Session): Session = transaction {
        val insertedId = SessionsTable.insert {
            it[tenantId]          = session.tenantId
            it[userId]            = session.userId
            it[clientId]          = session.clientId
            it[accessTokenHash]   = session.accessTokenHash
            it[refreshTokenHash]  = session.refreshTokenHash
            it[scopes]            = session.scopes
            it[ipAddress]         = session.ipAddress
            it[userAgent]         = session.userAgent
            it[createdAt]         = session.createdAt.toOffsetDateTime()
            it[expiresAt]         = session.expiresAt.toOffsetDateTime()
            it[refreshExpiresAt]  = session.refreshExpiresAt?.toOffsetDateTime()
            it[lastActivityAt]    = session.lastActivityAt.toOffsetDateTime()
            it[revokedAt]         = session.revokedAt?.toOffsetDateTime()
        } get SessionsTable.id

        session.copy(id = insertedId)
    }

    override fun findActiveByAccessTokenHash(hash: String): Session? = transaction {
        val now = OffsetDateTime.now()
        SessionsTable.selectAll()
            .where {
                (SessionsTable.accessTokenHash eq hash) and
                (SessionsTable.revokedAt.isNull()) and
                (SessionsTable.expiresAt greater now)
            }
            .map { it.toSession() }
            .singleOrNull()
    }

    override fun findActiveByRefreshTokenHash(hash: String): Session? = transaction {
        val now = OffsetDateTime.now()
        SessionsTable.selectAll()
            .where {
                (SessionsTable.refreshTokenHash eq hash) and
                (SessionsTable.revokedAt.isNull()) and
                (SessionsTable.refreshExpiresAt.isNotNull()) and
                (SessionsTable.refreshExpiresAt greater now)
            }
            .map { it.toSession() }
            .singleOrNull()
    }

    override fun revoke(sessionId: Int, revokedAt: Instant) = transaction {
        SessionsTable.update({ SessionsTable.id eq sessionId }) {
            it[SessionsTable.revokedAt] = revokedAt.toOffsetDateTime()
        }
        Unit
    }

    override fun revokeAllForUser(tenantId: Int, userId: Int, revokedAt: Instant) = transaction {
        val ts = revokedAt.toOffsetDateTime()
        SessionsTable.update({
            (SessionsTable.tenantId eq tenantId) and
            (SessionsTable.userId eq userId) and
            (SessionsTable.revokedAt.isNull())
        }) {
            it[SessionsTable.revokedAt] = ts
        }
        Unit
    }

    override fun findActiveByUser(tenantId: Int, userId: Int): List<Session> = transaction {
        val now = OffsetDateTime.now()
        SessionsTable.selectAll()
            .where {
                (SessionsTable.tenantId eq tenantId) and
                (SessionsTable.userId eq userId) and
                (SessionsTable.revokedAt.isNull()) and
                (SessionsTable.expiresAt greater now)
            }
            .orderBy(SessionsTable.createdAt, SortOrder.DESC)
            .map { it.toSession() }
    }

    override fun findById(id: Int): Session? = transaction {
        SessionsTable.selectAll()
            .where { SessionsTable.id eq id }
            .map { it.toSession() }
            .singleOrNull()
    }

    override fun findActiveByTenant(tenantId: Int): List<Session> = transaction {
        val now = OffsetDateTime.now()
        SessionsTable.selectAll()
            .where {
                (SessionsTable.tenantId eq tenantId) and
                (SessionsTable.revokedAt.isNull()) and
                (SessionsTable.expiresAt greater now)
            }
            .orderBy(SessionsTable.createdAt, SortOrder.DESC)
            .map { it.toSession() }
    }

    // -------------------------------------------------------------------------
    // Mappers
    // -------------------------------------------------------------------------

    private fun ResultRow.toSession() = Session(
        id                = this[SessionsTable.id],
        tenantId          = this[SessionsTable.tenantId],
        userId            = this[SessionsTable.userId],
        clientId          = this[SessionsTable.clientId],
        accessTokenHash   = this[SessionsTable.accessTokenHash],
        refreshTokenHash  = this[SessionsTable.refreshTokenHash],
        scopes            = this[SessionsTable.scopes],
        ipAddress         = this[SessionsTable.ipAddress],
        userAgent         = this[SessionsTable.userAgent],
        createdAt         = this[SessionsTable.createdAt].toInstant(),
        expiresAt         = this[SessionsTable.expiresAt].toInstant(),
        refreshExpiresAt  = this[SessionsTable.refreshExpiresAt]?.toInstant(),
        lastActivityAt    = this[SessionsTable.lastActivityAt].toInstant(),
        revokedAt         = this[SessionsTable.revokedAt]?.toInstant()
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
