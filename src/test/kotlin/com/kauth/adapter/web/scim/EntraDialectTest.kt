package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimPatchOpType
import com.kauth.domain.scim.ScimValue
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntraDialectTest {
    @Test
    fun `a string active value normalises to a boolean`() {
        val ops = EntraDialect.normalizeOps(fixture("entra/patch-active-string-false.json")).getOrThrow()
        assertEquals(1, ops.size)
        assertEquals(ScimValue.Bool(false), (ops.single().value as ScimValue.Complex).attributes["active"])
    }

    @Test
    fun `a capitalised op is lower-cased`() {
        val ops = EntraDialect.normalizeOps(fixture("entra/patch-capitalised-op.json")).getOrThrow()
        assertEquals(ScimPatchOpType.REPLACE, ops.single().op)
    }

    @Test
    fun `an unmappable payload fails rather than guessing`() {
        val result = EntraDialect.normalizeOps(Json.parseToJsonElement("""{"Operations":[{"op":"replace"}]}"""))
        assertTrue(result.isFailure)
    }

    @Test
    fun `a replace carrying an object value becomes a whole-resource merge`() {
        val op = EntraDialect.normalizeOps(fixture("entra/patch-replace-value-object.json")).getOrThrow().single()
        assertEquals(ScimPatchOpType.REPLACE, op.op)
        assertNull(op.path)
        val merged = op.value as ScimValue.Complex
        assertEquals(ScimValue.Str("Ada Lovelace"), merged.attributes["displayName"])
        assertEquals(ScimValue.Str("ada@example.com"), merged.attributes["userName"])
    }

    @Test
    fun `an operation with no path keeps its partial resource`() {
        val op = EntraDialect.normalizeOps(fixture("entra/patch-path-omitted.json")).getOrThrow().single()
        assertEquals(ScimPatchOpType.ADD, op.op)
        assertNull(op.path)
        assertTrue((op.value as ScimValue.Complex).attributes["emails"] is ScimValue.MultiValued)
    }

    @Test
    fun `a canonical remove passes through unchanged`() {
        val body = fixture("entra/patch-remove-member.json")
        assertEquals(RfcDialect.normalizeOps(body).getOrThrow(), EntraDialect.normalizeOps(body).getOrThrow())
    }

    @Test
    fun `a string active value in a resource body normalises to a boolean`() {
        val resource = EntraDialect.normalizeResource(fixture("entra/user-active-string-false.json")).getOrThrow()
        assertEquals(ScimValue.Bool(false), resource.attributes["active"])
        assertEquals(ScimValue.Str("ada@example.com"), resource.attributes["userName"])
    }

    @Test
    fun `a string that is not a boolean is left for the core to reject`() {
        val body = Json.parseToJsonElement("""{"schemas":[],"userName":"ada","active":"sometimes"}""")
        assertEquals(ScimValue.Str("sometimes"), EntraDialect.normalizeResource(body).getOrThrow().attributes["active"])
    }

    @Test
    fun `the dialect is reachable by its persisted id`() {
        assertEquals(EntraDialect, scimDialectFor("entra"))
    }
}
