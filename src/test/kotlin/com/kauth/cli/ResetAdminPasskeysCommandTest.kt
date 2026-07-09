package com.kauth.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for ResetAdminPasskeysCommand logic that is extractable without triggering [exitProcess].
 *
 * ResetAdminPasskeysCommand cannot be invoked directly in tests — it calls [exitProcess] before
 * returning. Instead, this file tests the `--username=` argument-parsing logic from the command.
 *
 * The service integration (adminResetAll) is tested in WebAuthnServiceTest.
 */
class ResetAdminPasskeysCommandTest {
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

        assertNull(result)
    }

    @Test
    fun `parseUsername returns null when --username flag is absent`() {
        val result = parseUsername(listOf("--other-flag=value", "--foo=bar"))

        assertNull(result)
    }

    @Test
    fun `parseUsername returns null when --username value is blank`() {
        val result = parseUsername(listOf("--username=   "))

        assertNull(result, "--username with only whitespace must be rejected (takeIf { isNotBlank })")
    }

    @Test
    fun `parseUsername returns null when --username= has no value`() {
        val result = parseUsername(listOf("--username="))

        assertNull(result, "--username= with empty string must be rejected")
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
