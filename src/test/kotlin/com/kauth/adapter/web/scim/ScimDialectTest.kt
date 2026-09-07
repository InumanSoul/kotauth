package com.kauth.adapter.web.scim

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ScimDialectTest {
    @Test
    fun `an unknown or absent dialect id falls back to the RFC pass-through`() {
        assertEquals("rfc", scimDialectFor(null).id)
        assertEquals("rfc", scimDialectFor("").id)
        assertEquals("rfc", scimDialectFor("not-a-dialect").id)
    }

    @Test
    fun `the RFC dialect changes nothing about a canonical body`() {
        val body =
            Json.parseToJsonElement(
                """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ada","active":true}""",
            )
        assertEquals(body.toScimResource().getOrThrow(), RfcDialect.normalizeResource(body).getOrThrow())
    }
}
