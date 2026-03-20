package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [TotpUtil] — RFC 6238 TOTP implementation.
 *
 * Tests cover: secret generation, Base32 encode/decode, URI formatting,
 * TOTP code generation/verification, and clock skew tolerance.
 */
class TotpUtilTest {
    // =========================================================================
    // generateSecret
    // =========================================================================

    @Test
    fun `generateSecret - returns a non-blank Base32 string`() {
        val secret = TotpUtil.generateSecret()
        assertTrue(secret.isNotBlank())
        assertTrue(secret.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }, "Secret must be valid Base32")
    }

    @Test
    fun `generateSecret - produces unique secrets on each call`() {
        val secrets = (1..10).map { TotpUtil.generateSecret() }.toSet()
        assertEquals(10, secrets.size, "10 calls should produce 10 unique secrets")
    }

    @Test
    fun `generateSecret - decodes back to 20 bytes (160-bit HMAC-SHA1 key)`() {
        val secret = TotpUtil.generateSecret()
        val decoded = TotpUtil.base32Decode(secret)
        assertEquals(20, decoded.size, "Secret must decode to 20 bytes for HMAC-SHA1")
    }

    // =========================================================================
    // base32Encode / base32Decode
    // =========================================================================

    @Test
    fun `base32 round-trip preserves arbitrary byte arrays`() {
        val original = byteArrayOf(0, 1, 2, 127, -128, -1, 42, 99)
        val encoded = TotpUtil.base32Encode(original)
        val decoded = TotpUtil.base32Decode(encoded)
        assertTrue(original.contentEquals(decoded), "Base32 round-trip must preserve byte content")
    }

    @Test
    fun `base32Encode - known value matches expected output`() {
        // "Hello" in ASCII = [72, 101, 108, 108, 111]
        // Base32("Hello") = "JBSWY3DP" (per RFC 4648 test vectors, no padding)
        val encoded = TotpUtil.base32Encode("Hello".toByteArray(Charsets.US_ASCII))
        assertEquals("JBSWY3DP", encoded)
    }

    @Test
    fun `base32Decode - handles lowercase input`() {
        val upper = TotpUtil.base32Decode("JBSWY3DP")
        val lower = TotpUtil.base32Decode("jbswy3dp")
        assertTrue(upper.contentEquals(lower), "Base32 decode should be case-insensitive")
    }

    @Test
    fun `base32Decode - strips padding characters`() {
        val withPadding = TotpUtil.base32Decode("JBSWY3DP======")
        val without = TotpUtil.base32Decode("JBSWY3DP")
        assertTrue(withPadding.contentEquals(without), "Padding should be stripped and ignored")
    }

    @Test
    fun `base32Decode - empty string returns empty array`() {
        val decoded = TotpUtil.base32Decode("")
        assertEquals(0, decoded.size)
    }

    // =========================================================================
    // generateUri
    // =========================================================================

    @Test
    fun `generateUri - contains required components`() {
        val secret = "JBSWY3DPEHPK3PXP"
        val uri = TotpUtil.generateUri(secret = secret, accountName = "alice@example.com", issuer = "Acme Corp")

        assertTrue(uri.startsWith("otpauth://totp/"), "Must use otpauth scheme")
        assertTrue(uri.contains("secret=$secret"), "Must include the secret")
        assertTrue(uri.contains("issuer=Acme"), "Must include the issuer")
        assertTrue(uri.contains("algorithm=SHA1"), "Must specify SHA1")
        assertTrue(uri.contains("digits=6"), "Must specify 6 digits")
        assertTrue(uri.contains("period=30"), "Must specify 30s period")
    }

    @Test
    fun `generateUri - URL-encodes special characters in issuer and account`() {
        val uri = TotpUtil.generateUri(
            secret = "ABCDEF",
            accountName = "user@example.com",
            issuer = "My Company & Co",
        )
        assertTrue(uri.contains("My+Company"), "Spaces and special chars should be URL-encoded")
        assertTrue(uri.contains("user%40example.com"), "@ should be URL-encoded")
    }

    // =========================================================================
    // generateCode
    // =========================================================================

    @Test
    fun `generateCode - returns a 6-digit string`() {
        val secret = TotpUtil.generateSecret()
        val code = TotpUtil.generateCode(secret)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() }, "Code must be all digits")
    }

    @Test
    fun `generateCode - same secret and time produce the same code`() {
        val secret = TotpUtil.generateSecret()
        val fixedTime = 1700000000000L
        val code1 = TotpUtil.generateCode(secret, fixedTime)
        val code2 = TotpUtil.generateCode(secret, fixedTime)
        assertEquals(code1, code2, "Same inputs must produce the same TOTP code")
    }

    @Test
    fun `generateCode - different time steps produce different codes`() {
        val secret = TotpUtil.generateSecret()
        val code1 = TotpUtil.generateCode(secret, 1700000000000L)
        val code2 = TotpUtil.generateCode(secret, 1700000060000L) // 60s later = different step
        assertNotEquals(code1, code2, "Different time steps should produce different codes")
    }

    // =========================================================================
    // verify
    // =========================================================================

    @Test
    fun `verify - accepts a valid code for the current time step`() {
        val secret = TotpUtil.generateSecret()
        val fixedTime = 1700000000000L
        val code = TotpUtil.generateCode(secret, fixedTime)
        assertTrue(TotpUtil.verify(secret, code, fixedTime))
    }

    @Test
    fun `verify - accepts code from adjacent time steps (clock skew tolerance)`() {
        val secret = TotpUtil.generateSecret()
        val fixedTime = 1700000000000L
        val codePrevStep = TotpUtil.generateCode(secret, fixedTime - 30_000L) // previous step
        val codeNextStep = TotpUtil.generateCode(secret, fixedTime + 30_000L) // next step

        assertTrue(TotpUtil.verify(secret, codePrevStep, fixedTime), "Should accept code from -1 time step")
        assertTrue(TotpUtil.verify(secret, codeNextStep, fixedTime), "Should accept code from +1 time step")
    }

    @Test
    fun `verify - rejects code from 2 steps away`() {
        val secret = TotpUtil.generateSecret()
        val fixedTime = 1700000000000L
        val codeFarFuture = TotpUtil.generateCode(secret, fixedTime + 90_000L) // +3 steps
        assertFalse(TotpUtil.verify(secret, codeFarFuture, fixedTime), "Should reject code from distant time step")
    }

    @Test
    fun `verify - rejects non-numeric code`() {
        val secret = TotpUtil.generateSecret()
        assertFalse(TotpUtil.verify(secret, "abcdef"))
    }

    @Test
    fun `verify - rejects code with wrong length`() {
        val secret = TotpUtil.generateSecret()
        assertFalse(TotpUtil.verify(secret, "12345"), "5-digit code should be rejected")
        assertFalse(TotpUtil.verify(secret, "1234567"), "7-digit code should be rejected")
    }

    @Test
    fun `verify - rejects empty code`() {
        val secret = TotpUtil.generateSecret()
        assertFalse(TotpUtil.verify(secret, ""))
    }
}
