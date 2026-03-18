package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [EncryptionService] — cookie signing + AES-GCM encryption.
 *
 * These tests require no external dependencies and run in-process.
 * EncryptionService is an object (singleton), so all tests share the same
 * HMAC key (derived from env or random fallback — both are fine for round-trip tests).
 */
class EncryptionServiceTest {
    // -------------------------------------------------------------------------
    // Cookie signing
    // -------------------------------------------------------------------------

    @Test
    fun `signCookie and verifyCookie round-trip returns original value`() {
        val original = "42|acme|1700000000000"
        val signed = EncryptionService.signCookie(original)

        val verified = EncryptionService.verifyCookie(signed)

        assertEquals(original, verified, "verifyCookie must return the original value on a valid signature")
    }

    @Test
    fun `verifyCookie rejects a tampered value`() {
        val original = "42|acme|1700000000000"
        val signed = EncryptionService.signCookie(original)

        // Flip one character in the value portion (before the last dot)
        val lastDot = signed.lastIndexOf('.')
        val tampered = "99|evil|1700000000000" + signed.substring(lastDot)

        val result = EncryptionService.verifyCookie(tampered)

        assertNull(result, "verifyCookie must return null when the value has been tampered with")
    }

    @Test
    fun `verifyCookie rejects a tampered signature`() {
        val original = "42|acme|1700000000000"
        val signed = EncryptionService.signCookie(original)

        // Truncate the last char of the signature to break it without changing length checks
        val lastDot = signed.lastIndexOf('.')
        val badSignature = signed.substring(lastDot + 1).dropLast(1) + "X"
        val tampered = signed.substring(0, lastDot + 1) + badSignature

        val result = EncryptionService.verifyCookie(tampered)

        assertNull(result, "verifyCookie must return null when the signature has been tampered with")
    }

    @Test
    fun `verifyCookie rejects a value with no dot separator`() {
        val result = EncryptionService.verifyCookie("nodotanywhere")
        assertNull(result, "verifyCookie must return null when there is no dot separator")
    }

    @Test
    fun `signCookie produces different signatures for different values`() {
        val sig1 = EncryptionService.signCookie("user:1")
        val sig2 = EncryptionService.signCookie("user:2")

        assertTrue(sig1 != sig2, "Different values must produce different signed cookies")
    }

    // -------------------------------------------------------------------------
    // AES-256-GCM encryption (only run if key is configured)
    // -------------------------------------------------------------------------

    @Test
    fun `encrypt and decrypt round-trip returns original plaintext when key is available`() {
        // If KAUTH_SECRET_KEY is not set in CI, EncryptionService.isAvailable is false.
        // We skip rather than fail — encryption requires the key; that is the intended design.
        if (!EncryptionService.isAvailable) return

        val plaintext = "smtp-password-super-secret"
        val encrypted = EncryptionService.encrypt(plaintext)
        val decrypted = EncryptionService.decrypt(encrypted)

        assertEquals(plaintext, decrypted, "decrypt must recover the original plaintext")
    }

    @Test
    fun `decrypt returns null for tampered ciphertext`() {
        if (!EncryptionService.isAvailable) return

        val encrypted = EncryptionService.encrypt("some-value")
        // Flip a character in the ciphertext part (after the first dot)
        val dotIdx = encrypted.indexOf('.')
        val corrupted = encrypted.substring(0, dotIdx + 1) + "AAAA" + encrypted.substring(dotIdx + 5)

        val result = EncryptionService.decrypt(corrupted)

        assertNull(result, "decrypt must return null when ciphertext has been tampered with")
    }
}
