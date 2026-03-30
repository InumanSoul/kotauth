package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [EncryptionService] — cookie signing + AES-GCM encryption.
 *
 * A known test key is injected via constructor so all tests are deterministic
 * and no longer depend on the KAUTH_SECRET_KEY environment variable.
 */
class EncryptionServiceTest {
    private val service = EncryptionService("test-secret-key-for-unit-tests")

    // -------------------------------------------------------------------------
    // Cookie signing
    // -------------------------------------------------------------------------

    @Test
    fun `signCookie and verifyCookie round-trip returns original value`() {
        val original = "42|acme|1700000000000"
        val signed = service.signCookie(original)

        val verified = service.verifyCookie(signed)

        assertEquals(original, verified, "verifyCookie must return the original value on a valid signature")
    }

    @Test
    fun `verifyCookie rejects a tampered value`() {
        val original = "42|acme|1700000000000"
        val signed = service.signCookie(original)

        val lastDot = signed.lastIndexOf('.')
        val tampered = "99|evil|1700000000000" + signed.substring(lastDot)

        val result = service.verifyCookie(tampered)

        assertNull(result, "verifyCookie must return null when the value has been tampered with")
    }

    @Test
    fun `verifyCookie rejects a tampered signature`() {
        val original = "42|acme|1700000000000"
        val signed = service.signCookie(original)

        val lastDot = signed.lastIndexOf('.')
        val badSignature = signed.substring(lastDot + 1).dropLast(1) + "X"
        val tampered = signed.substring(0, lastDot + 1) + badSignature

        val result = service.verifyCookie(tampered)

        assertNull(result, "verifyCookie must return null when the signature has been tampered with")
    }

    @Test
    fun `verifyCookie rejects a value with no dot separator`() {
        val result = service.verifyCookie("nodotanywhere")
        assertNull(result, "verifyCookie must return null when there is no dot separator")
    }

    @Test
    fun `signCookie produces different signatures for different values`() {
        val sig1 = service.signCookie("user:1")
        val sig2 = service.signCookie("user:2")

        assertTrue(sig1 != sig2, "Different values must produce different signed cookies")
    }

    // -------------------------------------------------------------------------
    // AES-256-GCM encryption — deterministic with known key
    // -------------------------------------------------------------------------

    @Test
    fun `encrypt and decrypt round-trip returns original plaintext`() {
        val plaintext = "smtp-password-super-secret"
        val encrypted = service.encrypt(plaintext)
        val decrypted = service.decrypt(encrypted)

        assertEquals(plaintext, decrypted, "decrypt must recover the original plaintext")
    }

    @Test
    fun `decrypt returns null for tampered ciphertext`() {
        val encrypted = service.encrypt("some-value")
        val dotIdx = encrypted.indexOf('.')
        val corrupted = encrypted.substring(0, dotIdx + 1) + "AAAA" + encrypted.substring(dotIdx + 5)

        val result = service.decrypt(corrupted)

        assertNull(result, "decrypt must return null when ciphertext has been tampered with")
    }

    @Test
    fun `encrypt produces different ciphertexts for the same plaintext (random IV)`() {
        val plaintext = "same-value-twice"
        val enc1 = service.encrypt(plaintext)
        val enc2 = service.encrypt(plaintext)

        assertNotEquals(enc1, enc2, "Each encryption should use a unique IV and produce different ciphertext")

        assertEquals(plaintext, service.decrypt(enc1))
        assertEquals(plaintext, service.decrypt(enc2))
    }

    @Test
    fun `encrypt output has expected iv-dot-ciphertext format`() {
        val encrypted = service.encrypt("test")
        val parts = encrypted.split(".")
        assertEquals(2, parts.size, "Encrypted value must have exactly two dot-separated parts (iv.ciphertext)")
        assertTrue(parts[0].isNotEmpty(), "IV part must not be empty")
        assertTrue(parts[1].isNotEmpty(), "Ciphertext part must not be empty")
    }

    @Test
    fun `decrypt returns null for empty string`() {
        assertNull(service.decrypt(""))
    }

    @Test
    fun `decrypt returns null for string without dot separator`() {
        assertNull(service.decrypt("nodothere"))
    }

    @Test
    fun `decrypt returns null for string with too many dots`() {
        val encrypted = service.encrypt("test")
        val corrupted = "$encrypted.extra"
        assertNull(service.decrypt(corrupted), "Three-part string should fail decryption")
    }

    // -------------------------------------------------------------------------
    // Additional cookie signing edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `signCookie - handles empty value`() {
        val signed = service.signCookie("")
        assertTrue(signed.startsWith("."), "Empty value should produce a cookie starting with dot")
        val verified = service.verifyCookie(signed)
        assertEquals("", verified, "Round-trip should return empty string")
    }

    @Test
    fun `signCookie - handles value containing dots`() {
        val value = "part1.part2.part3"
        val signed = service.signCookie(value)
        val verified = service.verifyCookie(signed)
        assertEquals(value, verified, "Values with dots should round-trip correctly (lastIndexOf split)")
    }

    @Test
    fun `signCookie - handles unicode content`() {
        val value = "user:42|café|日本語"
        val signed = service.signCookie(value)
        val verified = service.verifyCookie(signed)
        assertEquals(value, verified, "Unicode content should round-trip correctly")
    }

    @Test
    fun `verifyCookie - rejects completely empty string`() {
        assertNull(service.verifyCookie(""))
    }

    @Test
    fun `verifyCookie - rejects signature from a different value swapped in`() {
        val signed1 = service.signCookie("user:1|tenant:a")
        val signed2 = service.signCookie("user:2|tenant:b")

        val lastDot1 = signed1.lastIndexOf('.')
        val lastDot2 = signed2.lastIndexOf('.')
        val frankenCookie = signed1.substring(0, lastDot1) + signed2.substring(lastDot2)

        assertNull(
            service.verifyCookie(frankenCookie),
            "Mismatched value+signature should be rejected",
        )
    }

    @Test
    fun `signCookie is deterministic - same value produces same signature`() {
        val signed1 = service.signCookie("deterministic-test")
        val signed2 = service.signCookie("deterministic-test")
        assertEquals(signed1, signed2, "HMAC signing should be deterministic for the same key and value")
    }

    // -------------------------------------------------------------------------
    // Cross-instance isolation
    // -------------------------------------------------------------------------

    @Test
    fun `different keys produce different signatures`() {
        val other = EncryptionService("different-secret-key")
        val signed = service.signCookie("same-value")
        val otherSigned = other.signCookie("same-value")

        assertNotEquals(signed, otherSigned, "Different keys must produce different signatures")
        assertNull(other.verifyCookie(signed), "Cross-key verification must fail")
    }

    @Test
    fun `different keys cannot decrypt each other`() {
        val other = EncryptionService("different-secret-key")
        val encrypted = service.encrypt("secret-data")

        assertNull(other.decrypt(encrypted), "Cross-key decryption must fail")
    }
}
