package com.kauth.domain.scim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScimFilterParserTest {
    private fun ok(raw: String) = parseFilter(raw).getOrThrow()

    private fun failure(raw: String): ScimFailure = parseFilter(raw).exceptionOrNull() as ScimFailure

    @Test
    fun `parses a quoted string equality`() {
        assertEquals(ScimFilter.Eq("userName", ScimValue.Str("ada")), ok("""userName eq "ada""""))
    }

    @Test
    fun `parses boolean equality unquoted`() {
        assertEquals(ScimFilter.Eq("active", ScimValue.Bool(false)), ok("active eq false"))
    }

    @Test
    fun `parses and`() {
        assertEquals(
            ScimFilter.And(
                ScimFilter.Eq("userName", ScimValue.Str("ada")),
                ScimFilter.Eq("active", ScimValue.Bool(true)),
            ),
            ok("""userName eq "ada" and active eq true"""),
        )
    }

    @Test
    fun `parses or and respects parentheses`() {
        val f = ok("""(userName eq "a" or userName eq "b") and active eq true""")
        assertTrue(f is ScimFilter.And)
        assertTrue((f as ScimFilter.And).left is ScimFilter.Or)
    }

    @Test
    fun `unsupported operators fail loudly rather than matching everything`() {
        // Silently ignoring a filter makes a reconciliation pass believe the whole
        // directory is stale — the worst possible failure mode for provisioning.
        listOf("""userName co "ad"""", """userName sw "a"""", "userName pr", "id gt 5").forEach {
            assertEquals(ScimErrorType.invalidFilter, failure(it).type, "should reject: $it")
        }
    }

    @Test
    fun `unsupported attributes fail`() {
        assertEquals(ScimErrorType.invalidFilter, failure("""nickName eq "x"""").type)
    }

    @Test
    fun `malformed input fails without throwing`() {
        listOf("", "userName eq", "eq \"a\"", """userName eq "unterminated""", "((")
            .forEach { assertEquals(ScimErrorType.invalidFilter, failure(it).type, "should reject: $it") }
    }

    @Test
    fun `matches compares against a scim value`() {
        assertTrue(ok("""value eq "42"""").matches(ScimValue.Complex(mapOf("value" to ScimValue.Str("42")))))
    }

    @Test
    fun `nesting beyond the depth cap fails instead of overflowing the stack`() {
        // 40 opens comfortably exceeds any cap in the 32 range; if the cap were removed or
        // raised past 40 this would start throwing StackOverflowError instead of failing.
        val raw = "(".repeat(40) + "active eq true" + ")".repeat(40)
        assertEquals(ScimErrorType.invalidFilter, failure(raw).type)
    }

    @Test
    fun `reasonable nesting still parses`() {
        val raw = "(".repeat(20) + "active eq true" + ")".repeat(20)
        assertEquals(ScimFilter.Eq("active", ScimValue.Bool(true)), ok(raw))
    }
}
