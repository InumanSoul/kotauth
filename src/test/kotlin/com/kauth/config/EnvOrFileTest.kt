package com.kauth.config

import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnvOrFileTest {
    @Test
    fun `returns trimmed file contents when _FILE points to a readable file`() {
        val tmp = Files.createTempFile("kotauth-envorfile-", ".txt")
        try {
            tmp.writeText("super-secret-value\n")
            val env = mapOf("TEST_VAR_FILE" to tmp.toString())
            assertEquals("super-secret-value", envOrFile("TEST_VAR", env::get))
        } finally {
            tmp.deleteIfExists()
        }
    }

    @Test
    fun `_FILE wins over direct value when both are set`() {
        val tmp = Files.createTempFile("kotauth-envorfile-", ".txt")
        try {
            tmp.writeText("from-file")
            val env =
                mapOf(
                    "TEST_VAR" to "from-direct",
                    "TEST_VAR_FILE" to tmp.toString(),
                )
            assertEquals("from-file", envOrFile("TEST_VAR", env::get))
        } finally {
            tmp.deleteIfExists()
        }
    }

    @Test
    fun `returns direct value when _FILE is unset`() {
        val env = mapOf("TEST_VAR" to "direct-value")
        assertEquals("direct-value", envOrFile("TEST_VAR", env::get))
    }

    @Test
    fun `returns null when neither variable is set`() {
        val env = emptyMap<String, String>()
        assertNull(envOrFile("TEST_VAR", env::get))
    }

    @Test
    fun `_FILE pointing at a missing file throws`() {
        val env = mapOf("TEST_VAR_FILE" to "/tmp/kotauth-does-not-exist-${System.nanoTime()}")
        assertFailsWith<IOException> {
            envOrFile("TEST_VAR", env::get)
        }
    }

    @Test
    fun `trailing newline and surrounding whitespace are stripped`() {
        val tmp = Files.createTempFile("kotauth-envorfile-", ".txt")
        try {
            // Docker secrets typically end with a trailing newline; trim is the critical bit
            tmp.writeText("  spaces-and-newlines  \n\n")
            val env = mapOf("TEST_VAR_FILE" to tmp.toString())
            assertEquals("spaces-and-newlines", envOrFile("TEST_VAR", env::get))
        } finally {
            tmp.deleteIfExists()
        }
    }

    @Test
    fun `blank _FILE path falls back to the direct value`() {
        val env =
            mapOf(
                "TEST_VAR_FILE" to "   ",
                "TEST_VAR" to "direct-fallback",
            )
        assertEquals("direct-fallback", envOrFile("TEST_VAR", env::get))
    }
}
