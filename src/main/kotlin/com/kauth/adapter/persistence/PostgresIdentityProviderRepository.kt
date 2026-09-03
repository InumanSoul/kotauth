package com.kauth.adapter.persistence

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.EncryptionPort
import com.kauth.domain.port.IdentityProviderRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
class PostgresIdentityProviderRepository(
    private val encryptionService: EncryptionPort,
) : IdentityProviderRepository {
    override fun findEnabledByTenant(tenantId: TenantId): List<IdentityProvider> =
        transaction {
            IdentityProvidersTable
                .selectAll()
                .where {
                    (IdentityProvidersTable.tenantId eq tenantId.value) and
                        (IdentityProvidersTable.enabled eq true)
                }.orderBy(IdentityProvidersTable.provider to SortOrder.ASC)
                .mapNotNull { it.toIdentityProvider() }
        }

    override fun findAllByTenant(tenantId: TenantId): List<IdentityProvider> =
        transaction {
            IdentityProvidersTable
                .selectAll()
                .where { IdentityProvidersTable.tenantId eq tenantId.value }
                .orderBy(IdentityProvidersTable.provider to SortOrder.ASC)
                .mapNotNull { it.toIdentityProvider() }
        }

    override fun findById(
        tenantId: TenantId,
        id: Int,
    ): IdentityProvider? =
        transaction {
            IdentityProvidersTable
                .selectAll()
                .where {
                    (IdentityProvidersTable.tenantId eq tenantId.value) and
                        (IdentityProvidersTable.id eq id)
                }.singleOrNull()
                ?.toIdentityProvider()
        }

    override fun findByTenantAndProvider(
        tenantId: TenantId,
        provider: ProviderKey,
    ): IdentityProvider? =
        transaction {
            IdentityProvidersTable
                .selectAll()
                .where {
                    (IdentityProvidersTable.tenantId eq tenantId.value) and
                        (IdentityProvidersTable.provider eq provider.value)
                }.singleOrNull()
                ?.toIdentityProvider()
        }

    override fun save(provider: IdentityProvider): IdentityProvider =
        transaction {
            val encryptedSecret = encryptionService.encrypt(provider.clientSecret)
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val insertedId =
                IdentityProvidersTable.insert {
                    it[tenantId] = provider.tenantId.value
                    it[IdentityProvidersTable.provider] = provider.provider.value
                    it[clientId] = provider.clientId
                    it[clientSecret] = encryptedSecret
                    it[enabled] = provider.enabled
                    it[kind] = provider.kind.value
                    it[displayName] = provider.displayName
                    it[issuer] = provider.issuer
                    it[authorizationEndpoint] = provider.authorizationEndpoint
                    it[tokenEndpoint] = provider.tokenEndpoint
                    it[jwksUri] = provider.jwksUri
                    it[scopes] = provider.scopes
                    it[jitEnabled] = provider.jitEnabled
                    it[jitAllowedDomains] = provider.jitAllowedDomains.joinToStringOrNull()
                    it[createdAt] = now
                    it[updatedAt] = now
                } get IdentityProvidersTable.id

            IdentityProvidersTable
                .selectAll()
                .where { IdentityProvidersTable.id eq insertedId }
                .single()
                .toIdentityProvider()!!
        }

    override fun update(provider: IdentityProvider): IdentityProvider =
        transaction {
            val encryptedSecret = encryptionService.encrypt(provider.clientSecret)
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            IdentityProvidersTable.update({
                (IdentityProvidersTable.tenantId eq provider.tenantId.value) and
                    (IdentityProvidersTable.provider eq provider.provider.value)
            }) {
                it[clientId] = provider.clientId
                it[clientSecret] = encryptedSecret
                it[enabled] = provider.enabled
                it[kind] = provider.kind.value
                it[displayName] = provider.displayName
                it[issuer] = provider.issuer
                it[authorizationEndpoint] = provider.authorizationEndpoint
                it[tokenEndpoint] = provider.tokenEndpoint
                it[jwksUri] = provider.jwksUri
                it[scopes] = provider.scopes
                it[jitEnabled] = provider.jitEnabled
                it[jitAllowedDomains] = provider.jitAllowedDomains.joinToStringOrNull()
                it[updatedAt] = now
            }
            findByTenantAndProvider(provider.tenantId, provider.provider)!!
        }

    override fun delete(
        tenantId: TenantId,
        provider: ProviderKey,
    ) = transaction {
        IdentityProvidersTable.deleteWhere {
            (IdentityProvidersTable.tenantId eq tenantId.value) and
                (IdentityProvidersTable.provider eq provider.value)
        }
        Unit
    }

    // ------------------------------------------------------------------
    // Mapping helper
    // ------------------------------------------------------------------

    private fun ResultRow.toIdentityProvider(): IdentityProvider? {
        val providerKey =
            ProviderKey.of(this[IdentityProvidersTable.provider])
                ?: return null
        val decryptedSecret =
            encryptionService.decrypt(this[IdentityProvidersTable.clientSecret])
                ?: return null // cannot decrypt — encryption key may have changed; skip silently
        return IdentityProvider(
            id = this[IdentityProvidersTable.id],
            tenantId = TenantId(this[IdentityProvidersTable.tenantId]),
            provider = providerKey,
            clientId = this[IdentityProvidersTable.clientId],
            clientSecret = decryptedSecret,
            enabled = this[IdentityProvidersTable.enabled],
            kind = ProviderKind.of(this[IdentityProvidersTable.kind]) ?: ProviderKind.OAUTH2,
            displayName = this[IdentityProvidersTable.displayName],
            issuer = this[IdentityProvidersTable.issuer],
            authorizationEndpoint = this[IdentityProvidersTable.authorizationEndpoint],
            tokenEndpoint = this[IdentityProvidersTable.tokenEndpoint],
            jwksUri = this[IdentityProvidersTable.jwksUri],
            scopes = this[IdentityProvidersTable.scopes],
            jitEnabled = this[IdentityProvidersTable.jitEnabled],
            jitAllowedDomains = this[IdentityProvidersTable.jitAllowedDomains].splitDomains(),
            createdAt = this[IdentityProvidersTable.createdAt].toInstant(),
            updatedAt = this[IdentityProvidersTable.updatedAt].toInstant(),
        )
    }
}

// The list is normalised by IdentityProviderService before it ever reaches here, so these
// two are storage encoding only — not a second place where domains get cleaned up.
private fun List<String>.joinToStringOrNull(): String? = takeIf { it.isNotEmpty() }?.joinToString(",")

private fun String?.splitDomains(): List<String> = this?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
