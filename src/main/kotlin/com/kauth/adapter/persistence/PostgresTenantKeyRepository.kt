package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantKey
import com.kauth.domain.port.TenantKeyRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime

/**
 * Persistence adapter for tenant RSA signing keys.
 */
class PostgresTenantKeyRepository : TenantKeyRepository {

    override fun findActiveKey(tenantId: Int): TenantKey? = transaction {
        TenantKeysTable.selectAll()
            .where { (TenantKeysTable.tenantId eq tenantId) and (TenantKeysTable.enabled eq true) }
            .orderBy(TenantKeysTable.createdAt, SortOrder.DESC)
            .limit(1)
            .map { it.toTenantKey() }
            .singleOrNull()
    }

    override fun findEnabledKeys(tenantId: Int): List<TenantKey> = transaction {
        TenantKeysTable.selectAll()
            .where { (TenantKeysTable.tenantId eq tenantId) and (TenantKeysTable.enabled eq true) }
            .orderBy(TenantKeysTable.createdAt, SortOrder.DESC)
            .map { it.toTenantKey() }
    }

    override fun save(key: TenantKey): TenantKey = transaction {
        val insertedId = TenantKeysTable.insert {
            it[tenantId]   = key.tenantId
            it[keyId]      = key.keyId
            it[algorithm]  = key.algorithm
            it[publicKey]  = key.publicKeyPem
            it[privateKey] = key.privateKeyPem
            it[enabled]    = key.enabled
            it[createdAt]  = OffsetDateTime.now()
        } get TenantKeysTable.id

        key.copy(id = insertedId)
    }

    override fun disable(tenantId: Int, keyId: String) = transaction {
        TenantKeysTable.update({
            (TenantKeysTable.tenantId eq tenantId) and (TenantKeysTable.keyId eq keyId)
        }) {
            it[enabled] = false
        }
        Unit
    }

    private fun ResultRow.toTenantKey() = TenantKey(
        id            = this[TenantKeysTable.id],
        tenantId      = this[TenantKeysTable.tenantId],
        keyId         = this[TenantKeysTable.keyId],
        algorithm     = this[TenantKeysTable.algorithm],
        publicKeyPem  = this[TenantKeysTable.publicKey],
        privateKeyPem = this[TenantKeysTable.privateKey],
        enabled       = this[TenantKeysTable.enabled]
    )
}
