package com.kauth.adapter.persistence

import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ApiKeyRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// =============================================================================
// Table definition
// =============================================================================

object ApiKeysTable : Table("api_keys") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id")
    val name = varchar("name", 128)
    val keyPrefix = varchar("key_prefix", 16)
    val keyHash = varchar("key_hash", 64)
    val scopes = text("scopes")
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()
    val enabled = bool("enabled").default(true)
    val bootstrapName = varchar("bootstrap_name", 128).nullable()
    val scimDialect = varchar("scim_dialect", 16).default(ApiKey.DEFAULT_SCIM_DIALECT)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

// =============================================================================
// Repository
// =============================================================================

class PostgresApiKeyRepository : ApiKeyRepository {
    override fun save(apiKey: ApiKey): ApiKey =
        transaction {
            val insertedId =
                ApiKeysTable.insert {
                    it[tenantId] = apiKey.tenantId.value
                    it[name] = apiKey.name
                    it[keyPrefix] = apiKey.keyPrefix
                    it[keyHash] = apiKey.keyHash
                    it[scopes] = apiKey.scopes.joinToString(",")
                    it[expiresAt] = apiKey.expiresAt?.toOffsetDateTime()
                    it[enabled] = apiKey.enabled
                    it[bootstrapName] = apiKey.bootstrapName
                    it[scimDialect] = apiKey.scimDialect
                    it[createdAt] = apiKey.createdAt.toOffsetDateTime()
                } get ApiKeysTable.id

            apiKey.copy(id = insertedId)
        }

    override fun findByHash(hash: String): ApiKey? =
        transaction {
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.keyHash eq hash }
                .map { it.toApiKey() }
                .singleOrNull()
        }

    override fun findByTenantId(tenantId: TenantId): List<ApiKey> =
        transaction {
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.tenantId eq tenantId.value }
                .orderBy(ApiKeysTable.createdAt, SortOrder.DESC)
                .map { it.toApiKey() }
        }

    override fun findById(
        id: Int,
        tenantId: TenantId,
    ): ApiKey? =
        transaction {
            ApiKeysTable
                .selectAll()
                .where { (ApiKeysTable.id eq id) and (ApiKeysTable.tenantId eq tenantId.value) }
                .map { it.toApiKey() }
                .singleOrNull()
        }

    override fun revoke(
        id: Int,
        tenantId: TenantId,
    ) = transaction {
        ApiKeysTable.update({
            (ApiKeysTable.id eq id) and (ApiKeysTable.tenantId eq tenantId.value)
        }) {
            it[enabled] = false
        }
        Unit
    }

    override fun updateScimDialect(
        id: Int,
        tenantId: TenantId,
        scimDialect: String,
    ) = transaction {
        ApiKeysTable.update({
            (ApiKeysTable.id eq id) and (ApiKeysTable.tenantId eq tenantId.value)
        }) {
            it[ApiKeysTable.scimDialect] = scimDialect
        }
        Unit
    }

    override fun touchLastUsed(
        id: Int,
        at: Instant,
    ) = transaction {
        val ts: OffsetDateTime = at.toOffsetDateTime()
        ApiKeysTable.update({ ApiKeysTable.id eq id }) {
            it[lastUsedAt] = ts
        }
        Unit
    }

    override fun delete(
        id: Int,
        tenantId: TenantId,
    ) = transaction {
        ApiKeysTable.deleteWhere {
            (ApiKeysTable.id eq id) and (ApiKeysTable.tenantId eq tenantId.value)
        }
        Unit
    }

    override fun findByTenantAndName(
        tenantId: TenantId,
        name: String,
    ): ApiKey? =
        transaction {
            ApiKeysTable
                .selectAll()
                .where { (ApiKeysTable.tenantId eq tenantId.value) and (ApiKeysTable.name eq name) }
                .map { it.toApiKey() }
                .singleOrNull()
        }

    override fun updateBootstrap(
        id: Int,
        keyHash: String,
        scopes: List<String>,
        bootstrapName: String,
    ) = transaction {
        ApiKeysTable.update({ ApiKeysTable.id eq id }) {
            it[ApiKeysTable.keyHash] = keyHash
            it[ApiKeysTable.scopes] = scopes.joinToString(",")
            it[ApiKeysTable.enabled] = true
            it[ApiKeysTable.bootstrapName] = bootstrapName
        }
        Unit
    }

    // -------------------------------------------------------------------------
    // Mapper
    // -------------------------------------------------------------------------

    private fun ResultRow.toApiKey(): ApiKey {
        val expires: OffsetDateTime? = this[ApiKeysTable.expiresAt]
        val lastUsed: OffsetDateTime? = this[ApiKeysTable.lastUsedAt]
        val created: OffsetDateTime = this[ApiKeysTable.createdAt]
        return ApiKey(
            id = this[ApiKeysTable.id],
            tenantId = TenantId(this[ApiKeysTable.tenantId]),
            name = this[ApiKeysTable.name],
            keyPrefix = this[ApiKeysTable.keyPrefix],
            keyHash = this[ApiKeysTable.keyHash],
            scopes = this[ApiKeysTable.scopes].split(",").filter { it.isNotBlank() },
            expiresAt = expires?.toInstant(),
            lastUsedAt = lastUsed?.toInstant(),
            enabled = this[ApiKeysTable.enabled],
            bootstrapName = this[ApiKeysTable.bootstrapName],
            scimDialect = this[ApiKeysTable.scimDialect],
            createdAt = created.toInstant(),
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
