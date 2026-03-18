package com.kauth.adapter.persistence

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.infrastructure.EncryptionService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter — implements IdentityProviderRepository using PostgreSQL + Exposed.
 *
 * The client_secret is stored AES-256-GCM encrypted via EncryptionService.
 * On read, it is decrypted back to plaintext for use in OAuth2 token exchanges.
 * If KAUTH_SECRET_KEY is not set, save() will throw IllegalStateException — this
 * is intentional: social login cannot be configured without encryption being available.
 */
class PostgresIdentityProviderRepository : IdentityProviderRepository {
    override fun findEnabledByTenant(tenantId: Int): List<IdentityProvider> =
        transaction {
            IdentityProvidersTable
                .selectAll()
                .where { (IdentityProvidersTable.tenantId eq tenantId) and (IdentityProvidersTable.enabled eq true) }
                .mapNotNull { it.toIdentityProvider() }
        }

    override fun findAllByTenant(tenantId: Int): List<IdentityProvider> =
        transaction {
            IdentityProvidersTable
                .selectAll()
                .where { IdentityProvidersTable.tenantId eq tenantId }
                .mapNotNull { it.toIdentityProvider() }
        }

    override fun findByTenantAndProvider(
        tenantId: Int,
        provider: SocialProvider,
    ): IdentityProvider? =
        transaction {
            IdentityProvidersTable
                .selectAll()
                .where {
                    (IdentityProvidersTable.tenantId eq tenantId) and
                        (IdentityProvidersTable.provider eq provider.value)
                }.singleOrNull()
                ?.toIdentityProvider()
        }

    override fun save(idp: IdentityProvider): IdentityProvider =
        transaction {
            val encryptedSecret = EncryptionService.encrypt(idp.clientSecret)
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val insertedId =
                IdentityProvidersTable.insert {
                    it[tenantId] = idp.tenantId
                    it[provider] = idp.provider.value
                    it[clientId] = idp.clientId
                    it[clientSecret] = encryptedSecret
                    it[enabled] = idp.enabled
                    it[createdAt] = now
                    it[updatedAt] = now
                } get IdentityProvidersTable.id

            IdentityProvidersTable
                .selectAll()
                .where { IdentityProvidersTable.id eq insertedId }
                .single()
                .toIdentityProvider()!!
        }

    override fun update(idp: IdentityProvider): IdentityProvider =
        transaction {
            val encryptedSecret = EncryptionService.encrypt(idp.clientSecret)
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            IdentityProvidersTable.update({
                (IdentityProvidersTable.tenantId eq idp.tenantId) and
                    (IdentityProvidersTable.provider eq idp.provider.value)
            }) {
                it[clientId] = idp.clientId
                it[clientSecret] = encryptedSecret
                it[enabled] = idp.enabled
                it[updatedAt] = now
            }
            findByTenantAndProvider(idp.tenantId, idp.provider)!!
        }

    override fun delete(
        tenantId: Int,
        provider: SocialProvider,
    ) = transaction {
        IdentityProvidersTable.deleteWhere {
            (IdentityProvidersTable.tenantId eq tenantId) and
                (IdentityProvidersTable.provider eq provider.value)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // Mapping helper
    // ------------------------------------------------------------------

    private fun ResultRow.toIdentityProvider(): IdentityProvider? {
        val providerEnum =
            SocialProvider.fromValueOrNull(this[IdentityProvidersTable.provider])
                ?: return null
        val decryptedSecret =
            EncryptionService.decrypt(this[IdentityProvidersTable.clientSecret])
                ?: return null // cannot decrypt — encryption key may have changed; skip silently
        return IdentityProvider(
            id = this[IdentityProvidersTable.id],
            tenantId = this[IdentityProvidersTable.tenantId],
            provider = providerEnum,
            clientId = this[IdentityProvidersTable.clientId],
            clientSecret = decryptedSecret,
            enabled = this[IdentityProvidersTable.enabled],
            createdAt = this[IdentityProvidersTable.createdAt].toInstant(),
            updatedAt = this[IdentityProvidersTable.updatedAt].toInstant(),
        )
    }
}
