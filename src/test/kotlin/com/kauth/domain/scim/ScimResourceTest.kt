package com.kauth.domain.scim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScimResourceTest {
    @Test
    fun `get returns a nested attribute by name`() {
        val r =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes =
                    mapOf(
                        "userName" to ScimValue.Str("ada"),
                        "name" to ScimValue.Complex(mapOf("givenName" to ScimValue.Str("Ada"))),
                    ),
            )

        assertEquals(ScimValue.Str("ada"), r.attributes["userName"])
        assertEquals(
            ScimValue.Str("Ada"),
            (r.attributes["name"] as ScimValue.Complex).attributes["givenName"],
        )
        assertNull(r.attributes["missing"])
    }

    @Test
    fun `multi-valued attributes preserve order`() {
        val members =
            ScimValue.MultiValued(
                listOf(
                    ScimValue.Complex(mapOf("value" to ScimValue.Str("1"))),
                    ScimValue.Complex(mapOf("value" to ScimValue.Str("2"))),
                ),
            )
        assertEquals(2, members.values.size)
        assertEquals(ScimValue.Str("1"), (members.values[0] as ScimValue.Complex).attributes["value"])
    }

    @Test
    fun `Null is distinct from an absent attribute`() {
        // SCIM distinguishes "explicitly cleared" from "not sent" — PUT clears,
        // PATCH without the attribute does not.
        val r = ScimResource(schemas = emptyList(), attributes = mapOf("nickName" to ScimValue.Null))
        assertEquals(ScimValue.Null, r.attributes["nickName"])
        assertNull(r.attributes["absent"])
    }
}
