package com.kauth.adapter.social

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for the bounded body read. The streaming ceiling is what keeps a mistyped issuer URL
 * pointing at something large from becoming an OOM, so it is tested directly rather than through
 * a socket — the suite has no network.
 */
class HttpJsonFetcherTest {
    private val url = "https://issuer.example/.well-known/openid-configuration"

    @Test
    fun `a body under the ceiling is read whole`() {
        val body = """{"issuer":"https://issuer.example"}"""

        assertEquals(body, readBounded(ByteArrayInputStream(body.toByteArray()), 1_024L, url))
    }

    @Test
    fun `a body of exactly the ceiling is accepted`() {
        val body = "x".repeat(64)

        assertEquals(body, readBounded(ByteArrayInputStream(body.toByteArray()), 64L, url))
    }

    @Test
    fun `one byte over the ceiling is refused`() {
        val body = "x".repeat(65)

        val failure =
            assertFailsWith<ResponseTooLargeException> {
                readBounded(ByteArrayInputStream(body.toByteArray()), 64L, url)
            }

        assertEquals(64L, failure.maxBytes)
    }

    @Test
    fun `a body far over the ceiling stops at the ceiling rather than buffering it all`() {
        val body = "x".repeat(1_000_000)

        assertFailsWith<ResponseTooLargeException> {
            readBounded(ByteArrayInputStream(body.toByteArray()), 8L, url)
        }
    }

    @Test
    fun `multi-byte characters are decoded as utf-8`() {
        val body = """{"display":"Oriána"}"""

        assertEquals(body, readBounded(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), 1_024L, url))
    }
}
