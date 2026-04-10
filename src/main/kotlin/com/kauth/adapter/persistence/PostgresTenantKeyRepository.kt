package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey
import com.kauth.domain.port.EncryptionPort
import com.kauth.domain.port.TenantKeyRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime

/** Persistence adapter for tenant RSA signing keys. Private keys are AES-256-GCM encrypted at rest. */
class PostgresTenantKeyRepository(
    private val encryptionService: EncryptionPort,
) : TenantKeyRepository {
    override fun findActiveKey(tenantId: TenantId): TenantKey? =
        transaction {
            TenantKeysTable
                .selectAll()
                .where {
                    (TenantKeysTable.tenantId eq tenantId.value) and
                        (TenantKeysTable.active eq true) and
                        (TenantKeysTable.enabled eq true)
                }.map { it.toTenantKey() }
                .singleOrNull()
        }

    override fun findEnabledKeys(tenantId: TenantId): List<TenantKey> =
        transaction {
            TenantKeysTable
                .selectAll()
                .where { (TenantKeysTable.tenantId eq tenantId.value) and (TenantKeysTable.enabled eq true) }
                .orderBy(TenantKeysTable.createdAt, SortOrder.DESC)
                .map { it.toTenantKey() }
        }

    override fun findAllKeys(tenantId: TenantId): List<TenantKey> =
        transaction {
            TenantKeysTable
                .selectAll()
                .where { TenantKeysTable.tenantId eq tenantId.value }
                .orderBy(TenantKeysTable.createdAt, SortOrder.DESC)
                .map { it.toTenantKey() }
        }

    override fun findByKeyId(
        tenantId: TenantId,
        keyId: String,
    ): TenantKey? =
        transaction {
            TenantKeysTable
                .selectAll()
                .where { (TenantKeysTable.tenantId eq tenantId.value) and (TenantKeysTable.keyId eq keyId) }
                .map { it.toTenantKey() }
                .singleOrNull()
        }

    override fun save(key: TenantKey): TenantKey =
        transaction {
            val insertedId =
                TenantKeysTable.insert {
                    it[tenantId] = key.tenantId.value
                    it[keyId] = key.keyId
                    it[algorithm] = key.algorithm
                    it[publicKey] = key.publicKeyPem
                    it[privateKey] = encryptionService.encrypt(key.privateKeyPem)
                    it[enabled] = key.enabled
                    it[active] = key.active
                    it[createdAt] = OffsetDateTime.now()
                } get TenantKeysTable.id

            key.copy(id = insertedId)
        }

    override fun rotate(
        tenantId: TenantId,
        newKeyId: String,
        previousKeyId: String,
    ) = transaction {
        // Demote old signing key (keep enabled=true for JWKS verification)
        TenantKeysTable.update({
            (TenantKeysTable.tenantId eq tenantId.value) and (TenantKeysTable.keyId eq previousKeyId)
        }) { it[active] = false }

        // Promote new signing key
        TenantKeysTable.update({
            (TenantKeysTable.tenantId eq tenantId.value) and (TenantKeysTable.keyId eq newKeyId)
        }) { it[active] = true }
        Unit
    }

    override fun disable(
        tenantId: TenantId,
        keyId: String,
    ) = transaction {
        TenantKeysTable.update({
            (TenantKeysTable.tenantId eq tenantId.value) and (TenantKeysTable.keyId eq keyId)
        }) {
            it[enabled] = false
            it[active] = false
        }
        Unit
    }

    private fun ResultRow.toTenantKey(): TenantKey {
        val storedPrivateKey = this[TenantKeysTable.privateKey]
        val decryptedPrivateKey = encryptionService.decrypt(storedPrivateKey) ?: storedPrivateKey
        return TenantKey(
            id = this[TenantKeysTable.id],
            tenantId = TenantId(this[TenantKeysTable.tenantId]),
            keyId = this[TenantKeysTable.keyId],
            algorithm = this[TenantKeysTable.algorithm],
            publicKeyPem = this[TenantKeysTable.publicKey],
            privateKeyPem = decryptedPrivateKey,
            enabled = this[TenantKeysTable.enabled],
            active = this[TenantKeysTable.active],
            createdAt = this[TenantKeysTable.createdAt].toInstant(),
        )
    }
}
