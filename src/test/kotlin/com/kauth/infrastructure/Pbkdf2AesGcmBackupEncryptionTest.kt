package com.kauth.infrastructure

import com.kauth.domain.port.BackupDecryptResult
import com.kauth.domain.port.BackupDecryptionError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real PBKDF2/AES-GCM round-trip — exercises the production envelope adapter.
 *
 * Kept minimal because each call runs 600k PBKDF2 iterations. Heavier behavior
 * (every backup option matrix) is exercised via the in-memory FakeBackupEncryptionPort
 * in BackupExportImportTest.
 */
class Pbkdf2AesGcmBackupEncryptionTest {
    private val crypto = Pbkdf2AesGcmBackupEncryption()

    @Test
    fun `round trip recovers plaintext`() {
        val pass = "correct horse battery staple".toCharArray()
        val plaintext = """{"hello":"world","schema":38}"""
        val envelope = crypto.encrypt(plaintext, pass)
        assertTrue(envelope.startsWith(Pbkdf2AesGcmBackupEncryption.VERSION_PREFIX))
        when (val r = crypto.decrypt(envelope, pass)) {
            is BackupDecryptResult.Success -> assertEquals(plaintext, r.plaintext)
            is BackupDecryptResult.Failure -> fail("Expected success, got ${r.error}")
        }
    }

    @Test
    fun `wrong passphrase returns WrongPassphrase`() {
        val envelope = crypto.encrypt("payload", "right".toCharArray())
        when (val r = crypto.decrypt(envelope, "wrong".toCharArray())) {
            is BackupDecryptResult.Success -> fail("Decryption should have failed under wrong passphrase")
            is BackupDecryptResult.Failure ->
                assertEquals(BackupDecryptionError.WrongPassphrase, r.error)
        }
    }

    @Test
    fun `malformed envelope returns MalformedEnvelope`() {
        when (val r = crypto.decrypt("not-an-envelope", "x".toCharArray())) {
            is BackupDecryptResult.Failure ->
                assertEquals(BackupDecryptionError.MalformedEnvelope, r.error)
            else -> fail("Expected failure")
        }
    }

    @Test
    fun `unknown bkp version returns UnsupportedVersion`() {
        when (val r = crypto.decrypt("bkp9.aaaa.bbbb.cccc", "x".toCharArray())) {
            is BackupDecryptResult.Failure -> {
                assertTrue(r.error is BackupDecryptionError.UnsupportedVersion)
                assertEquals("bkp9", r.error.version)
            }
            else -> fail("Expected failure")
        }
    }

    @Test
    fun `each encryption uses fresh salt and nonce`() {
        val pass = "same".toCharArray()
        val a = crypto.encrypt("payload", pass)
        val b = crypto.encrypt("payload", pass)
        assertNotEquals(
            a,
            b,
            "Salt + nonce must be random per call so the same plaintext yields a different envelope each time",
        )
    }
}
