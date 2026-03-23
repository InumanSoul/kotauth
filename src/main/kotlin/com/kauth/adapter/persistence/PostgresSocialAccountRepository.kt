package com.kauth.adapter.persistence

import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
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
        tenantId: TenantId,
        provider: SocialProvider,
        providerUserId: String,
    ): SocialAccount? =
        transaction {
            SocialAccountsTable
                .selectAll()
                .where {
                    (SocialAccountsTable.tenantId eq tenantId.value) and
                        (SocialAccountsTable.provider eq provider.value) and
                        (SocialAccountsTable.providerUserId eq providerUserId)
                }.singleOrNull()
                ?.toSocialAccount()
        }

    override fun findByUserId(userId: UserId): List<SocialAccount> =
        transaction {
            SocialAccountsTable
                .selectAll()
                .where { SocialAccountsTable.userId eq userId.value }
                .map { it.toSocialAccount() }
        }

    override fun save(account: SocialAccount): SocialAccount =
        transaction {
            val insertedId =
                SocialAccountsTable.insert {
                    it[userId] = account.userId.value
                    it[tenantId] = account.tenantId.value
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
        userId: UserId,
        provider: SocialProvider,
    ) = transaction {
        SocialAccountsTable.deleteWhere {
            (SocialAccountsTable.userId eq userId.value) and
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
            userId = UserId(this[SocialAccountsTable.userId]),
            tenantId = TenantId(this[SocialAccountsTable.tenantId]),
            provider = SocialProvider.fromValue(this[SocialAccountsTable.provider]),
            providerUserId = this[SocialAccountsTable.providerUserId],
            providerEmail = this[SocialAccountsTable.providerEmail],
            providerName = this[SocialAccountsTable.providerName],
            avatarUrl = this[SocialAccountsTable.avatarUrl],
            linkedAt = this[SocialAccountsTable.linkedAt].toInstant(),
        )
}
