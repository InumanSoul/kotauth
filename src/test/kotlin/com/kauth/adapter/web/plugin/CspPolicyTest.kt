package com.kauth.adapter.web.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CspPolicyTest {
    @Test
    fun `empty origin set produces self-only form-action`() {
        val csp = buildCspPolicy(emptySet())
        assertTrue(csp.contains("form-action 'self'"))
        assertTrue(!csp.contains("form-action 'self' "))
    }

    @Test
    fun `single origin is appended after self`() {
        val csp = buildCspPolicy(setOf("https://desk.example.com"))
        assertTrue(csp.contains("form-action 'self' https://desk.example.com"))
    }

    @Test
    fun `multiple origins are space-separated after self`() {
        val csp = buildCspPolicy(setOf("https://a.example.com", "https://b.example.com"))
        val fa = csp.substringAfter("form-action ").substringBefore(";").trim()
        assertTrue(fa.startsWith("'self' "))
        val origins = fa.removePrefix("'self' ").split(" ").toSet()
        assertEquals(setOf("https://a.example.com", "https://b.example.com"), origins)
    }

    @Test
    fun `directives are semicolon-separated and include the standard set`() {
        val csp = buildCspPolicy()
        val directives = csp.split("; ").map { it.substringBefore(" ") }.toSet()
        assertEquals(
            setOf("default-src", "script-src", "style-src", "font-src", "img-src", "form-action"),
            directives,
        )
    }
}
