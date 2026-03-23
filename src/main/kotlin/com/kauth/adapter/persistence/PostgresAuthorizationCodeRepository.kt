package com.kauth.adapter.persistence

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuthorizationCodeRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter for OAuth2 authorization codes.
 */
class PostgresAuthorizationCodeRepository : AuthorizationCodeRepository {
    override fun save(code: AuthorizationCode): AuthorizationCode =
        transaction {
            val insertedId =
                AuthorizationCodesTable.insert {
                    it[AuthorizationCodesTable.code] = code.code
                    it[tenantId] = code.tenantId.value
                    it[clientId] = code.clientId.value
                    it[userId] = code.userId.value
                    it[redirectUri] = code.redirectUri
                    it[scopes] = code.scopes
                    it[codeChallenge] = code.codeChallenge
                    it[codeChallengeMethod] = code.codeChallengeMethod
                    it[nonce] = code.nonce
                    it[state] = code.state
                    it[expiresAt] = code.expiresAt.toOffsetDateTime()
                    it[usedAt] = code.usedAt?.toOffsetDateTime()
                    it[createdAt] = code.createdAt.toOffsetDateTime()
                } get AuthorizationCodesTable.id

            code.copy(id = insertedId)
        }

    override fun findByCode(code: String): AuthorizationCode? =
        transaction {
            AuthorizationCodesTable
                .selectAll()
                .where { AuthorizationCodesTable.code eq code }
                .map { it.toAuthCode() }
                .singleOrNull()
        }

    override fun markUsed(
        code: String,
        usedAt: Instant,
    ) = transaction {
        AuthorizationCodesTable.update({ AuthorizationCodesTable.code eq code }) {
            it[AuthorizationCodesTable.usedAt] = usedAt.toOffsetDateTime()
        }
        Unit
    }

    // -------------------------------------------------------------------------
    // Mappers
    // -------------------------------------------------------------------------

    private fun ResultRow.toAuthCode() =
        AuthorizationCode(
            id = this[AuthorizationCodesTable.id],
            code = this[AuthorizationCodesTable.code],
            tenantId = TenantId(this[AuthorizationCodesTable.tenantId]),
            clientId = ApplicationId(this[AuthorizationCodesTable.clientId]),
            userId = UserId(this[AuthorizationCodesTable.userId]),
            redirectUri = this[AuthorizationCodesTable.redirectUri],
            scopes = this[AuthorizationCodesTable.scopes],
            codeChallenge = this[AuthorizationCodesTable.codeChallenge],
            codeChallengeMethod = this[AuthorizationCodesTable.codeChallengeMethod],
            nonce = this[AuthorizationCodesTable.nonce],
            state = this[AuthorizationCodesTable.state],
            expiresAt = this[AuthorizationCodesTable.expiresAt].toInstant(),
            usedAt = this[AuthorizationCodesTable.usedAt]?.toInstant(),
            createdAt = this[AuthorizationCodesTable.createdAt].toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
