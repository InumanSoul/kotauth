package com.kauth.cli

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for CLI logic that is extractable without triggering [exitProcess].
 *
 * Neither [GenerateSecretKeyCommand] nor [ResetAdminMfaCommand] can be invoked
 * directly in tests — both call [exitProcess] before returning. Instead, this
 * file tests the *algorithms* those commands depend on by replicating the
 * exact logic from the source, verifying the contracts hold.
 *
 * What is tested:
 * - The hex-formatting algorithm used by [GenerateSecretKeyCommand.execute]
 * - The `--username=` argument-parsing logic from [ResetAdminMfaCommand.parseUsername]
 */
class CliRunnerTest {
    // =========================================================================
    // GenerateSecretKeyCommand — hex generation algorithm
    // =========================================================================

    @Test
    fun `generated secret key is 64 character lowercase hex`() {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }

        assertEquals(64, hex.length, "32 bytes formatted as %02x must produce exactly 64 characters")
        assertTrue(
            hex.all { it in '0'..'9' || it in 'a'..'f' },
            "Output must contain only lowercase hex characters [0-9a-f]",
        )
    }

    @Test
    fun `generated secret key is different on each call`() {
        fun generate(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        val key1 = generate()
        val key2 = generate()

        assertTrue(key1 != key2, "Two independent key generations must not produce the same value")
    }

    @Test
    fun `hex format pads single-digit byte values with leading zero`() {
        // Force bytes that are known to require leading-zero padding (0x00–0x0F)
        val bytes = ByteArray(2) { 0x00.toByte() }
        bytes[1] = 0x0F.toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }

        assertEquals("000f", hex, "%02x must zero-pad values below 0x10")
    }

    // =========================================================================
    // ResetAdminMfaCommand — parseUsername argument-parsing logic
    //
    // The private function cannot be called directly, so we replicate the
    // exact implementation here to verify the parsing contract.
    // =========================================================================

    private fun parseUsername(args: List<String>): String? =
        args.firstNotNullOfOrNull { arg ->
            when {
                arg.startsWith("--username=") -> arg.removePrefix("--username=").takeIf { it.isNotBlank() }
                else -> null
            }
        }

    @Test
    fun `parseUsername extracts username from --username=value`() {
        val result = parseUsername(listOf("--username=admin"))

        assertEquals("admin", result)
    }

    @Test
    fun `parseUsername returns null when no arguments provided`() {
        val result = parseUsername(emptyList())

        assertEquals(null, result)
    }

    @Test
    fun `parseUsername returns null when --username flag is absent`() {
        val result = parseUsername(listOf("--other-flag=value", "--foo=bar"))

        assertEquals(null, result)
    }

    @Test
    fun `parseUsername returns null when --username value is blank`() {
        val result = parseUsername(listOf("--username=   "))

        assertEquals(null, result, "--username with only whitespace must be rejected (takeIf { isNotBlank })")
    }

    @Test
    fun `parseUsername returns null when --username= has no value`() {
        val result = parseUsername(listOf("--username="))

        assertEquals(null, result, "--username= with empty string must be rejected")
    }

    @Test
    fun `parseUsername picks first matching flag when multiple are present`() {
        val result = parseUsername(listOf("--username=first", "--username=second"))

        assertEquals("first", result, "firstNotNullOfOrNull must stop at the first valid match")
    }

    @Test
    fun `parseUsername ignores non-matching flags before the username flag`() {
        val result = parseUsername(listOf("--verbose", "--dry-run", "--username=bob"))

        assertEquals("bob", result)
    }

    @Test
    fun `parseUsername handles username containing hyphens`() {
        val result = parseUsername(listOf("--username=super-admin"))

        assertEquals("super-admin", result, "Usernames with hyphens are valid and must be parsed correctly")
    }
}
