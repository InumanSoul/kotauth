package com.kauth.adapter.persistence

import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.SocialAccountRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter — implements SocialAccountRepository using PostgreSQL + Exposed.
 */
class PostgresSocialAccountRepository : SocialAccountRepository {
    override fun findByProviderIdentity(
        tenantId: Int,
        provider: SocialProvider,
        providerUserId: String,
    ): SocialAccount? =
        transaction {
            SocialAccountsTable
                .selectAll()
                .where {
                    (SocialAccountsTable.tenantId eq tenantId) and
                        (SocialAccountsTable.provider eq provider.value) and
                        (SocialAccountsTable.providerUserId eq providerUserId)
                }.singleOrNull()
                ?.toSocialAccount()
        }

    override fun findByUserId(userId: Int): List<SocialAccount> =
        transaction {
            SocialAccountsTable
                .selectAll()
                .where { SocialAccountsTable.userId eq userId }
                .map { it.toSocialAccount() }
        }

    override fun save(account: SocialAccount): SocialAccount =
        transaction {
            val insertedId =
                SocialAccountsTable.insert {
                    it[userId] = account.userId
                    it[tenantId] = account.tenantId
                    it[provider] = account.provider.value
                    it[providerUserId] = account.providerUserId
                    it[providerEmail] = account.providerEmail
                    it[providerName] = account.providerName
                    it[avatarUrl] = account.avatarUrl
                    it[linkedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                } get SocialAccountsTable.id

            SocialAccountsTable
                .selectAll()
                .where { SocialAccountsTable.id eq insertedId }
                .single()
                .toSocialAccount()
        }

    override fun delete(
        userId: Int,
        provider: SocialProvider,
    ) = transaction {
        SocialAccountsTable.deleteWhere {
            (SocialAccountsTable.userId eq userId) and
                (SocialAccountsTable.provider eq provider.value)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // Mapping helper
    // ------------------------------------------------------------------

    private fun ResultRow.toSocialAccount(): SocialAccount =
        SocialAccount(
            id = this[SocialAccountsTable.id],
            userId = this[SocialAccountsTable.userId],
            tenantId = this[SocialAccountsTable.tenantId],
            provider = SocialProvider.fromValue(this[SocialAccountsTable.provider]),
            providerUserId = this[SocialAccountsTable.providerUserId],
            providerEmail = this[SocialAccountsTable.providerEmail],
            providerName = this[SocialAccountsTable.providerName],
            avatarUrl = this[SocialAccountsTable.avatarUrl],
            linkedAt = this[SocialAccountsTable.linkedAt].toInstant(),
        )
}
