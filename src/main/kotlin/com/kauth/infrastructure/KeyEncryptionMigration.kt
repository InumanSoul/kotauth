package com.kauth.infrastructure

import com.kauth.adapter.persistence.TenantKeysTable
import com.kauth.domain.port.EncryptionPort
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * One-time startup migration: encrypts any plaintext RSA private keys in the database.
 *
 * Plaintext PEM keys start with "-----BEGIN PRIVATE KEY-----". Encrypted values
 * use the EncryptionService format (base64url iv.ciphertext) and never match that prefix.
 * This makes the check idempotent — already-encrypted keys are skipped.
 */
class KeyEncryptionMigration(
    private val encryptionService: EncryptionPort,
) {
    private val log = LoggerFactory.getLogger(KeyEncryptionMigration::class.java)

    fun migrateIfNeeded() {
        transaction {
            TenantKeysTable.selectAll().forEach { row ->
                val privateKey = row[TenantKeysTable.privateKey]
                if (privateKey.startsWith("-----BEGIN")) {
                    val encrypted = encryptionService.encrypt(privateKey)
                    TenantKeysTable.update({ TenantKeysTable.id eq row[TenantKeysTable.id] }) {
                        it[TenantKeysTable.privateKey] = encrypted
                    }
                    log.info(
                        "Encrypted private key for tenant {}, keyId={}",
                        row[TenantKeysTable.tenantId],
                        row[TenantKeysTable.keyId],
                    )
                }
            }
        }
    }
}
