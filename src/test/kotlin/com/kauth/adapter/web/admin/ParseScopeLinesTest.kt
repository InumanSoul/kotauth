package com.kauth.adapter.web.admin

import kotlin.test.Test
import kotlin.test.assertEquals

class ParseScopeLinesTest {
    @Test
    fun `text after a scope on its line is that scope's consent text`() {
        val (scopes, descriptions) =
            parseScopeLines(
                "read:orders   See your orders\n\nwrite:orders\n  read:orders duplicate ignored\nadmin\tFull access",
            )

        assertEquals(listOf("read:orders", "write:orders", "admin"), scopes)
        assertEquals(mapOf("read:orders" to "See your orders", "admin" to "Full access"), descriptions)
    }

    @Test
    fun `no input declares nothing`() {
        assertEquals(emptyList<String>() to emptyMap(), parseScopeLines(null))
    }
}
