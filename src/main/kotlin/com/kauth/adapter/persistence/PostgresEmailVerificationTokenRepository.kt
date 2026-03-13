package com.kauth.adapter.persistence

import com.kauth.domain.model.EmailVerificationToken
import com.kauth.domain.port.EmailVerificationTokenRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter for email verification tokens.
 * Tokens are keyed by SHA-256 hash — the raw token is never stored.
 */
class PostgresEmailVerificationTokenRepository : EmailVerificationTokenRepository {

    override fun create(token: EmailVerificationToken): EmailVerificationToken = transaction {
        val insertedId = EmailVerificationTokensTable.insert {
            it[userId]    = token.userId
            it[tenantId]  = token.tenantId
            it[tokenHash] = token.tokenHash
            it[expiresAt] = token.expiresAt.toOffsetDateTime()
            it[createdAt] = token.createdAt.toOffsetDateTime()
        } get EmailVerificationTokensTable.id

        token.copy(id = insertedId)
    }

    override fun findByTokenHash(hash: String): EmailVerificationToken? = transaction {
        EmailVerificationTokensTable.selectAll()
            .where { EmailVerificationTokensTable.tokenHash eq hash }
            .map { it.toToken() }
            .singleOrNull()
    }

    override fun markUsed(tokenId: Int, usedAt: Instant) = transaction {
        EmailVerificationTokensTable.update({ EmailVerificationTokensTable.id eq tokenId }) {
            it[EmailVerificationTokensTable.usedAt] = usedAt.toOffsetDateTime()
        }
        Unit
    }

    override fun deleteUnusedByUser(userId: Int) = transaction {
        EmailVerificationTokensTable.deleteWhere {
            (EmailVerificationTokensTable.userId eq userId) and
            (EmailVerificationTokensTable.usedAt.isNull())
        }
        Unit
    }

    private fun ResultRow.toToken() = EmailVerificationToken(
        id        = this[EmailVerificationTokensTable.id],
        userId    = this[EmailVerificationTokensTable.userId],
        tenantId  = this[EmailVerificationTokensTable.tenantId],
        tokenHash = this[EmailVerificationTokensTable.tokenHash],
        expiresAt = this[EmailVerificationTokensTable.expiresAt].toInstant(),
        usedAt    = this[EmailVerificationTokensTable.usedAt]?.toInstant(),
        createdAt = this[EmailVerificationTokensTable.createdAt].toInstant()
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
