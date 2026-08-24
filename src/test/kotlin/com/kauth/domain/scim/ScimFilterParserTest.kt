package com.kauth.domain.scim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScimFilterParserTest {
    private fun ok(
        raw: String,
        scope: ScimFilterScope = ScimFilterScope.USER,
    ) = parseFilter(raw, scope).getOrThrow()

    private fun failure(
        raw: String,
        scope: ScimFilterScope = ScimFilterScope.USER,
    ): ScimFailure = parseFilter(raw, scope).exceptionOrNull() as ScimFailure

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
        assertTrue(
            ok("""value eq "42"""", ScimFilterScope.VALUE_PATH)
                .matches(ScimValue.Complex(mapOf("value" to ScimValue.Str("42")))),
        )
    }

    @Test
    fun `a User attribute in a Group filter is invalidFilter, not an empty result set`() {
        // A 200 with totalResults 0 reads as "that group does not exist", and a provisioning
        // client acts on it by creating a duplicate.
        val failure = failure("""userName eq "ada"""", ScimFilterScope.GROUP)

        assertEquals(ScimErrorType.invalidFilter, failure.type)
        assertTrue(failure.detail.contains("Group"), failure.detail)
    }

    @Test
    fun `a Group attribute in a User filter is invalidFilter too`() {
        assertEquals(ScimErrorType.invalidFilter, failure("""members eq "42"""", ScimFilterScope.USER).type)
    }

    @Test
    fun `an element sub-attribute is only filterable inside a valued path`() {
        assertEquals(ScimErrorType.invalidFilter, failure("""value eq "42"""", ScimFilterScope.USER).type)
        assertEquals(ScimErrorType.invalidFilter, failure("""userName eq "ada"""", ScimFilterScope.VALUE_PATH).type)
    }

    @Test
    fun `displayName and externalId stay filterable on both resource types`() {
        listOf(ScimFilterScope.USER, ScimFilterScope.GROUP).forEach { scope ->
            assertEquals(ScimFilter.Eq("displayName", ScimValue.Str("Eng")), ok("""displayName eq "Eng"""", scope))
            assertEquals(ScimFilter.Eq("externalId", ScimValue.Str("x")), ok("""externalId eq "x"""", scope))
        }
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
