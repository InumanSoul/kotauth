package com.kauth.adapter.persistence

import com.kauth.domain.model.ApiKey
import com.kauth.domain.port.ApiKeyRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// =============================================================================
// Table definition
// =============================================================================

object ApiKeysTable : Table("api_keys") {
    val id          = integer("id").autoIncrement()
    val tenantId    = integer("tenant_id")
    val name        = varchar("name", 128)
    val keyPrefix   = varchar("key_prefix", 16)
    val keyHash     = varchar("key_hash", 64)
    val scopes      = text("scopes")
    val expiresAt   = timestampWithTimeZone("expires_at").nullable()
    val lastUsedAt  = timestampWithTimeZone("last_used_at").nullable()
    val enabled     = bool("enabled").default(true)
    val createdAt   = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

// =============================================================================
// Repository
// =============================================================================

class PostgresApiKeyRepository : ApiKeyRepository {

    override fun save(apiKey: ApiKey): ApiKey = transaction {
        val insertedId = ApiKeysTable.insert {
            it[tenantId]  = apiKey.tenantId
            it[name]      = apiKey.name
            it[keyPrefix] = apiKey.keyPrefix
            it[keyHash]   = apiKey.keyHash
            it[scopes]    = apiKey.scopes.joinToString(",")
            it[expiresAt] = apiKey.expiresAt?.toOffsetDateTime()
            it[enabled]   = apiKey.enabled
            it[createdAt] = apiKey.createdAt.toOffsetDateTime()
        } get ApiKeysTable.id

        apiKey.copy(id = insertedId)
    }

    override fun findByHash(hash: String): ApiKey? = transaction {
        ApiKeysTable.selectAll()
            .where { ApiKeysTable.keyHash eq hash }
            .map { it.toApiKey() }
            .singleOrNull()
    }

    override fun findByTenantId(tenantId: Int): List<ApiKey> = transaction {
        ApiKeysTable.selectAll()
            .where { ApiKeysTable.tenantId eq tenantId }
            .orderBy(ApiKeysTable.createdAt, SortOrder.DESC)
            .map { it.toApiKey() }
    }

    override fun findById(id: Int, tenantId: Int): ApiKey? = transaction {
        ApiKeysTable.selectAll()
            .where { (ApiKeysTable.id eq id) and (ApiKeysTable.tenantId eq tenantId) }
            .map { it.toApiKey() }
            .singleOrNull()
    }

    override fun revoke(id: Int, tenantId: Int) = transaction {
        ApiKeysTable.update({
            (ApiKeysTable.id eq id) and (ApiKeysTable.tenantId eq tenantId)
        }) {
            it[enabled] = false
        }
        Unit
    }

    override fun touchLastUsed(id: Int, at: Instant) = transaction {
        val ts: OffsetDateTime = at.toOffsetDateTime()
        ApiKeysTable.update({ ApiKeysTable.id eq id }) {
            it[lastUsedAt] = ts
        }
        Unit
    }

    override fun delete(id: Int, tenantId: Int) = transaction {
        ApiKeysTable.deleteWhere {
            (ApiKeysTable.id eq id) and (ApiKeysTable.tenantId eq tenantId)
        }
        Unit
    }

    // -------------------------------------------------------------------------
    // Mapper
    // -------------------------------------------------------------------------

    private fun ResultRow.toApiKey(): ApiKey {
        val expires  : OffsetDateTime? = this[ApiKeysTable.expiresAt]
        val lastUsed : OffsetDateTime? = this[ApiKeysTable.lastUsedAt]
        val created  : OffsetDateTime  = this[ApiKeysTable.createdAt]
        return ApiKey(
            id         = this[ApiKeysTable.id],
            tenantId   = this[ApiKeysTable.tenantId],
            name       = this[ApiKeysTable.name],
            keyPrefix  = this[ApiKeysTable.keyPrefix],
            keyHash    = this[ApiKeysTable.keyHash],
            scopes     = this[ApiKeysTable.scopes].split(",").filter { it.isNotBlank() },
            expiresAt  = expires?.toInstant(),
            lastUsedAt = lastUsed?.toInstant(),
            enabled    = this[ApiKeysTable.enabled],
            createdAt  = created.toInstant()
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
