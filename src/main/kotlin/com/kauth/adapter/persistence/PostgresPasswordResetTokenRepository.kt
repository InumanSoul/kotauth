package com.kauth.adapter.persistence

import com.kauth.domain.model.PasswordResetToken
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.PasswordResetTokenRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter for password reset tokens.
 * Tokens are keyed by SHA-256 hash — the raw token is never stored.
 */
class PostgresPasswordResetTokenRepository : PasswordResetTokenRepository {
    override fun create(token: PasswordResetToken): PasswordResetToken =
        transaction {
            val insertedId =
                PasswordResetTokensTable.insert {
                    it[userId] = token.userId.value
                    it[tenantId] = token.tenantId.value
                    it[tokenHash] = token.tokenHash
                    it[expiresAt] = token.expiresAt.toOffsetDateTime()
                    it[ipAddress] = token.ipAddress
                    it[createdAt] = token.createdAt.toOffsetDateTime()
                } get PasswordResetTokensTable.id

            token.copy(id = insertedId)
        }

    override fun findByTokenHash(hash: String): PasswordResetToken? =
        transaction {
            PasswordResetTokensTable
                .selectAll()
                .where { PasswordResetTokensTable.tokenHash eq hash }
                .map { it.toToken() }
                .singleOrNull()
        }

    override fun markUsed(
        tokenId: Int,
        usedAt: Instant,
    ) = transaction {
        PasswordResetTokensTable.update({ PasswordResetTokensTable.id eq tokenId }) {
            it[PasswordResetTokensTable.usedAt] = usedAt.toOffsetDateTime()
        }
        Unit
    }

    override fun deleteByUser(userId: UserId) =
        transaction {
            PasswordResetTokensTable.deleteWhere {
                PasswordResetTokensTable.userId eq userId.value
            }
            Unit
        }

    private fun ResultRow.toToken() =
        PasswordResetToken(
            id = this[PasswordResetTokensTable.id],
            userId = UserId(this[PasswordResetTokensTable.userId]),
            tenantId = TenantId(this[PasswordResetTokensTable.tenantId]),
            tokenHash = this[PasswordResetTokensTable.tokenHash],
            expiresAt = this[PasswordResetTokensTable.expiresAt].toInstant(),
            usedAt = this[PasswordResetTokensTable.usedAt]?.toInstant(),
            ipAddress = this[PasswordResetTokensTable.ipAddress],
            createdAt = this[PasswordResetTokensTable.createdAt].toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
