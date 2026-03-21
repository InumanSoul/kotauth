package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun `encrypt produces different ciphertexts for the same plaintext (random IV)`() {
        if (!EncryptionService.isAvailable) return

        val plaintext = "same-value-twice"
        val enc1 = EncryptionService.encrypt(plaintext)
        val enc2 = EncryptionService.encrypt(plaintext)

        assertNotEquals(enc1, enc2, "Each encryption should use a unique IV and produce different ciphertext")

        assertEquals(plaintext, EncryptionService.decrypt(enc1))
        assertEquals(plaintext, EncryptionService.decrypt(enc2))
    }

    @Test
    fun `encrypt output has expected iv-dot-ciphertext format`() {
        if (!EncryptionService.isAvailable) return

        val encrypted = EncryptionService.encrypt("test")
        val parts = encrypted.split(".")
        assertEquals(2, parts.size, "Encrypted value must have exactly two dot-separated parts (iv.ciphertext)")
        assertTrue(parts[0].isNotEmpty(), "IV part must not be empty")
        assertTrue(parts[1].isNotEmpty(), "Ciphertext part must not be empty")
    }

    @Test
    fun `decrypt returns null for empty string`() {
        assertNull(EncryptionService.decrypt(""))
    }

    @Test
    fun `decrypt returns null for string without dot separator`() {
        assertNull(EncryptionService.decrypt("nodothere"))
    }

    @Test
    fun `decrypt returns null for string with too many dots`() {
        if (!EncryptionService.isAvailable) return

        val encrypted = EncryptionService.encrypt("test")
        val corrupted = "$encrypted.extra"
        assertNull(EncryptionService.decrypt(corrupted), "Three-part string should fail decryption")
    }

    // -------------------------------------------------------------------------
    // Additional cookie signing edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `signCookie - handles empty value`() {
        val signed = EncryptionService.signCookie("")
        assertTrue(signed.startsWith("."), "Empty value should produce a cookie starting with dot")
        val verified = EncryptionService.verifyCookie(signed)
        assertEquals("", verified, "Round-trip should return empty string")
    }

    @Test
    fun `signCookie - handles value containing dots`() {
        val value = "part1.part2.part3"
        val signed = EncryptionService.signCookie(value)
        val verified = EncryptionService.verifyCookie(signed)
        assertEquals(value, verified, "Values with dots should round-trip correctly (lastIndexOf split)")
    }

    @Test
    fun `signCookie - handles unicode content`() {
        val value = "user:42|café|日本語"
        val signed = EncryptionService.signCookie(value)
        val verified = EncryptionService.verifyCookie(signed)
        assertEquals(value, verified, "Unicode content should round-trip correctly")
    }

    @Test
    fun `verifyCookie - rejects completely empty string`() {
        assertNull(EncryptionService.verifyCookie(""))
    }

    @Test
    fun `verifyCookie - rejects signature from a different value swapped in`() {
        val signed1 = EncryptionService.signCookie("user:1|tenant:a")
        val signed2 = EncryptionService.signCookie("user:2|tenant:b")

        // Take the value from signed1 and the signature from signed2
        val lastDot1 = signed1.lastIndexOf('.')
        val lastDot2 = signed2.lastIndexOf('.')
        val frankenCookie = signed1.substring(0, lastDot1) + signed2.substring(lastDot2)

        assertNull(
            EncryptionService.verifyCookie(frankenCookie),
            "Mismatched value+signature should be rejected",
        )
    }

    @Test
    fun `signCookie is deterministic - same value produces same signature`() {
        val signed1 = EncryptionService.signCookie("deterministic-test")
        val signed2 = EncryptionService.signCookie("deterministic-test")
        assertEquals(signed1, signed2, "HMAC signing should be deterministic for the same key and value")
    }
}
