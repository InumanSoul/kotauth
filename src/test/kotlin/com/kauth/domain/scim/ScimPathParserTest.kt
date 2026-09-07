package com.kauth.domain.scim

import kotlin.test.Test
import kotlin.test.assertEquals

class ScimPathParserTest {
    private fun ok(raw: String) = parsePath(raw).getOrThrow()

    private fun failure(raw: String) = parsePath(raw).exceptionOrNull() as ScimFailure

    @Test
    fun `simple attribute`() {
        assertEquals(ScimPath.Attr(urn = null, name = "active", sub = null), ok("active"))
    }

    @Test
    fun `sub-attribute`() {
        assertEquals(ScimPath.Attr(urn = null, name = "name", sub = "givenName"), ok("name.givenName"))
    }

    @Test
    fun `urn-qualified attribute`() {
        assertEquals(
            ScimPath.Attr(urn = "urn:ietf:params:scim:schemas:core:2.0:User", name = "userName", sub = null),
            ok("urn:ietf:params:scim:schemas:core:2.0:User:userName"),
        )
    }

    @Test
    fun `valued path without sub-attribute`() {
        val p = ok("""members[value eq "42"]""") as ScimPath.Valued
        assertEquals("members", p.attr.name)
        assertEquals(ScimFilter.Eq("value", ScimValue.Str("42")), p.filter)
        assertEquals(null, p.sub)
    }

    @Test
    fun `valued path with sub-attribute`() {
        val p = ok("""emails[type eq "work"].value""") as ScimPath.Valued
        assertEquals("emails", p.attr.name)
        assertEquals("value", p.sub)
    }

    @Test
    fun `multi-valued attribute with no filter parses as a plain attribute`() {
        assertEquals(ScimPath.Attr(urn = null, name = "members", sub = null), ok("members"))
    }

    @Test
    fun `malformed paths fail without throwing`() {
        listOf("", "   ", "members[", "members[]", """members[value eq "42"""", "name..givenName", ".name")
            .forEach { assertEquals(ScimErrorType.invalidPath, failure(it).type, "should reject: $it") }
    }

    @Test
    fun `a bad filter inside a valued path surfaces as invalidPath`() {
        // The caller is patching, not querying — the actionable error is the path.
        assertEquals(ScimErrorType.invalidPath, failure("""members[value co "4"]""").type)
    }
}
