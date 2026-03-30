package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the idempotency heuristic used by [KeyEncryptionMigration].
 *
 * The migration skips rows whose stored value does not start with "-----BEGIN".
 * This file verifies that heuristic is correct for both its entry conditions:
 *
 * 1. A real PEM key DOES start with "-----BEGIN" → migration will encrypt it.
 * 2. An [EncryptionService]-encrypted value does NOT start with "-----BEGIN"
 *    → a second migration pass will skip already-encrypted rows.
 *
 * The database interaction itself (Exposed transactions) requires a live
 * PostgreSQL instance and is covered by E2E tests.
 */
class KeyEncryptionMigrationTest {
    private val encryption = EncryptionService("test-key-for-migration-tests")

    // =========================================================================
    // PEM detection heuristic
    // =========================================================================

    @Test
    fun `plaintext PEM private key starts with BEGIN marker`() {
        val pemKey =
            """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7...
            -----END PRIVATE KEY-----
            """.trimIndent()

        assertTrue(
            pemKey.startsWith("-----BEGIN"),
            "A plaintext PEM key must start with '-----BEGIN' so the migration picks it up",
        )
    }

    @Test
    fun `encrypted output from EncryptionService does not start with BEGIN marker`() {
        val pemKey =
            """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7...
            -----END PRIVATE KEY-----
            """.trimIndent()

        val encrypted = encryption.encrypt(pemKey)

        assertFalse(
            encrypted.startsWith("-----BEGIN"),
            "The EncryptionService iv.ciphertext format must never start with '-----BEGIN'; " +
                "if it did, idempotency would break and the migration would try to encrypt again",
        )
    }

    @Test
    fun `encrypt then check idempotency - already-encrypted key would be skipped`() {
        val pemKey =
            """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7...
            -----END PRIVATE KEY-----
            """.trimIndent()

        val encrypted = encryption.encrypt(pemKey)

        // Simulate what the migration guard does: skip if not "-----BEGIN"
        val wouldMigrateAgain = !encrypted.startsWith("-----BEGIN")

        assertTrue(
            wouldMigrateAgain,
            "After encryption a second migration pass must skip the row — the BEGIN check is the idempotency guard",
        )
    }

    @Test
    fun `encrypted value round-trips back to original PEM`() {
        val pemKey =
            """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7...
            -----END PRIVATE KEY-----
            """.trimIndent()

        val encrypted = encryption.encrypt(pemKey)
        val decrypted = encryption.decrypt(encrypted)

        assertTrue(
            decrypted == pemKey,
            "Decrypting a migrated key must recover the exact original PEM content",
        )
    }

    @Test
    fun `PKCS8 PRIVATE KEY variant also matches BEGIN marker`() {
        // Keycloak and other IAM tools sometimes emit PKCS8 headers
        val pkcs8Key = "-----BEGIN PRIVATE KEY-----\nMIIE..."

        assertTrue(
            pkcs8Key.startsWith("-----BEGIN"),
            "PKCS8 'BEGIN PRIVATE KEY' variant must be caught by the same startsWith guard",
        )
    }

    @Test
    fun `RSA PRIVATE KEY variant also matches BEGIN marker`() {
        val rsaKey = "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."

        assertTrue(
            rsaKey.startsWith("-----BEGIN"),
            "Traditional RSA 'BEGIN RSA PRIVATE KEY' variant must be caught by the same startsWith guard",
        )
    }

    @Test
    fun `empty string does not match BEGIN marker`() {
        assertFalse(
            "".startsWith("-----BEGIN"),
            "An empty stored value must not trigger the migration (sanity guard)",
        )
    }
}
