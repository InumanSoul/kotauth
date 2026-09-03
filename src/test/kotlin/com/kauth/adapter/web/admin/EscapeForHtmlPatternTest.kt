package com.kauth.adapter.web.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The confirm-by-typing gate's HTML5 `pattern` attribute.
 *
 * It was built with `java.util.regex.Pattern.quote`, which emits the Java-only `\Q…\E`
 * form. A `pattern` the browser cannot compile is discarded outright, so the attribute
 * silently stopped constraining anything. These assert the output is plain ECMAScript.
 */
class EscapeForHtmlPatternTest {
    @Test
    fun `a hyphenated slug still matches itself once escaped`() {
        // '-' is escaped because it is a range operator inside a character class; the
        // escape is harmless outside one, and the pattern must still match the slug.
        val escaped = escapeForHtmlPattern("acme-corp")

        assertEquals("acme\\-corp", escaped)
        assertTrue(Regex(escaped).matches("acme-corp"))
    }

    @Test
    fun `never emits the Java-only quote form`() {
        val escaped = escapeForHtmlPattern("acme")

        assertTrue("\\Q" !in escaped && "\\E" !in escaped, "Java's \\Q…\\E does not compile as ECMAScript: $escaped")
    }

    @Test
    fun `escapes the metacharacters an API identifier can contain`() {
        // Resource-server identifiers are URIs, so dots, slashes and colons are routine.
        val escaped = escapeForHtmlPattern("https://api.example.com/v1")

        assertEquals("https:\\/\\/api\\.example\\.com\\/v1", escaped)
    }

    @Test
    fun `escaped output matches the literal it came from and nothing else`() {
        val literal = "a.c"
        val regex = Regex(escapeForHtmlPattern(literal))

        assertTrue(regex.matches(literal))
        assertTrue(!regex.matches("abc"), "An unescaped '.' would let any character through")
    }
}
