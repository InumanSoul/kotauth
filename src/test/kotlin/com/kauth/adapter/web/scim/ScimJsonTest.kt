package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimPatchOpType
import com.kauth.domain.scim.ScimPath
import com.kauth.domain.scim.ScimResource
import com.kauth.domain.scim.ScimValue
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScimJsonTest {
    @Test
    fun `a resource round-trips to JSON and back unchanged`() {
        val resource =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes =
                    mapOf(
                        "userName" to ScimValue.Str("ada"),
                        "active" to ScimValue.Bool(true),
                        "age" to ScimValue.Num(30),
                        "name" to
                            ScimValue.Complex(
                                mapOf(
                                    "givenName" to ScimValue.Str("Ada"),
                                    "familyName" to ScimValue.Str("Lovelace"),
                                ),
                            ),
                        "emails" to
                            ScimValue.MultiValued(
                                listOf(ScimValue.Complex(mapOf("value" to ScimValue.Str("ada@example.com")))),
                            ),
                        // Null nested inside Complex/MultiValued, not just at the top level.
                        "clearedNested" to ScimValue.Complex(mapOf("nickName" to ScimValue.Null)),
                        "clearedInList" to
                            ScimValue.MultiValued(
                                listOf(ScimValue.Complex(mapOf("note" to ScimValue.Null))),
                            ),
                        "bigNum" to ScimValue.Num(Long.MAX_VALUE),
                    ),
            )

        val json = resource.toJson()
        // Serialize and reparse to exercise the actual wire format, not just the in-memory tree.
        val reparsed = Json.parseToJsonElement(json.toString())
        val decoded = reparsed.toScimResource().getOrThrow()

        assertEquals(resource, decoded)
    }

    @Test
    fun `a JSON null decodes to explicit Null and an absent key decodes to no entry`() {
        val json = Json.parseToJsonElement("""{"schemas":[],"nickName":null}""")

        val decoded = json.toScimResource().getOrThrow()

        assertEquals(ScimValue.Null, decoded.attributes["nickName"])
        assertTrue("nickName" in decoded.attributes)
        assertFalse("displayName" in decoded.attributes)
        assertNull(decoded.attributes["displayName"])
    }

    @Test
    fun `a PATCH body decodes Operations including one with no path`() {
        val json =
            Json.parseToJsonElement(
                """
                {
                  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                  "Operations": [
                    {"op": "replace", "path": "active", "value": false},
                    {"op": "add", "value": {"givenName": "Ada"}}
                  ]
                }
                """.trimIndent(),
            )

        val ops = json.toScimPatchOps().getOrThrow()

        assertEquals(2, ops.size)
        val first = ops[0]
        assertEquals(ScimPatchOpType.REPLACE, first.op)
        assertEquals(ScimValue.Bool(false), first.value)
        val path = assertIs<ScimPath.Attr>(first.path)
        assertEquals("active", path.name)

        val second = ops[1]
        assertEquals(ScimPatchOpType.ADD, second.op)
        assertNull(second.path)
        assertEquals(ScimValue.Complex(mapOf("givenName" to ScimValue.Str("Ada"))), second.value)
    }

    @Test
    fun `an unparseable path in an operation surfaces as invalidPath, not a decode crash`() {
        val json =
            Json.parseToJsonElement(
                """{"Operations": [{"op": "replace", "path": "1invalid", "value": true}]}""",
            )

        val result = json.toScimPatchOps()

        assertTrue(result.isFailure)
        val failure = assertIs<ScimFailure>(result.exceptionOrNull())
        assertEquals(ScimErrorType.invalidPath, failure.type)
    }

    @Test
    fun `a uniqueness failure maps to 409 with the SCIM error schema and scimType`() {
        val failure = ScimFailure(ScimErrorType.uniqueness, "userName already exists")

        val (status, body) = failure.toResponse()

        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals(409, status.value)
        val schemas = body["schemas"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("urn:ietf:params:scim:api:messages:2.0:Error"), schemas)
        assertEquals("uniqueness", body["scimType"]?.jsonPrimitive?.content)
        assertEquals("userName already exists", body["detail"]?.jsonPrimitive?.content)
        // status is a SCIM wire STRING, not a JSON number.
        assertTrue(body["status"]?.jsonPrimitive?.isString == true)
        assertEquals("409", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `every ScimErrorType maps to a status`() {
        val expected =
            mapOf(
                ScimErrorType.uniqueness to HttpStatusCode.Conflict,
                ScimErrorType.invalidFilter to HttpStatusCode.BadRequest,
                ScimErrorType.invalidPath to HttpStatusCode.BadRequest,
                ScimErrorType.invalidValue to HttpStatusCode.BadRequest,
                ScimErrorType.invalidSyntax to HttpStatusCode.BadRequest,
                ScimErrorType.mutability to HttpStatusCode.BadRequest,
                ScimErrorType.noTarget to HttpStatusCode.BadRequest,
            )

        for (type in ScimErrorType.entries) {
            val (status, body) = ScimFailure(type, "detail").toResponse()
            val expectedStatus =
                expected[type] ?: error("no expected status mapping for $type — add one to this test")
            assertEquals(expectedStatus, status, "unexpected status for $type")
            assertEquals(
                expectedStatus.value.toString(),
                body["status"]?.jsonPrimitive?.content,
                "status must be a string for $type",
            )
            assertEquals(type.name, body["scimType"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a resource JsonElement that is not an object fails with invalidSyntax`() {
        val result = Json.parseToJsonElement("[]").toScimResource()

        assertTrue(result.isFailure)
        assertEquals(ScimErrorType.invalidSyntax, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `a PATCH body missing Operations fails with invalidSyntax`() {
        val result = Json.parseToJsonElement("""{"schemas":[]}""").toScimPatchOps()

        assertTrue(result.isFailure)
        assertEquals(ScimErrorType.invalidSyntax, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `the object case of toScimResource strips schemas from attributes`() {
        val json = Json.parseToJsonElement("""{"schemas":["urn:x"],"userName":"ada"}""").jsonObject

        val decoded = json.toScimResource().getOrThrow()

        assertEquals(listOf("urn:x"), decoded.schemas)
        assertFalse("schemas" in decoded.attributes)
        assertEquals(ScimValue.Str("ada"), decoded.attributes["userName"])
    }

    @Test
    fun `a body nested beyond the depth cap fails with invalidSyntax instead of overflowing the stack`() {
        // 50 levels comfortably exceeds the 32-level cap — unambiguously "too deep", not a
        // boundary case that could fail for an unrelated reason.
        val json = Json.parseToJsonElement("""{"schemas":[],"attr":${nestedObjectJson(50)}}""")

        val result = json.toScimResource()

        assertTrue(result.isFailure)
        assertEquals(ScimErrorType.invalidSyntax, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `a body nested to a reasonable depth still decodes`() {
        val json = Json.parseToJsonElement("""{"schemas":[],"attr":${nestedObjectJson(5)}}""")

        val decoded = json.toScimResource().getOrThrow()

        var value = decoded.attributes.getValue("attr")
        repeat(5) { value = (value as ScimValue.Complex).attributes.getValue("nested") }
        assertEquals(ScimValue.Str("leaf"), value)
    }

    @Test
    fun `a fractional number fails with invalidSyntax rather than silently becoming a string`() {
        val json = Json.parseToJsonElement("""{"schemas":[],"score":3.14}""")

        val result = json.toScimResource()

        assertTrue(result.isFailure)
        assertEquals(ScimErrorType.invalidSyntax, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `a number overflowing Long fails with invalidSyntax rather than silently becoming a string`() {
        val json = Json.parseToJsonElement("""{"schemas":[],"huge":99999999999999999999}""")

        val result = json.toScimResource()

        assertTrue(result.isFailure)
        assertEquals(ScimErrorType.invalidSyntax, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `an unknown PATCH op verb fails with invalidSyntax`() {
        val json = Json.parseToJsonElement("""{"Operations":[{"op":"frobnicate","path":"active","value":true}]}""")

        val result = json.toScimPatchOps()

        assertTrue(result.isFailure)
        assertEquals(ScimErrorType.invalidSyntax, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `an empty Operations array decodes to an empty operation list`() {
        // The codec doesn't enforce "at least one operation" — that's left to whatever
        // applies the ops, so an empty array is a structurally valid, if vacuous, PATCH body.
        val json = Json.parseToJsonElement("""{"Operations":[]}""")

        val ops = json.toScimPatchOps().getOrThrow()

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `an add operation with no value decodes with a null value`() {
        // Whether "add" requires a value is the patch engine's business, not the codec's —
        // a missing value is structurally fine here and surfaces downstream as invalidValue.
        val json = Json.parseToJsonElement("""{"Operations":[{"op":"add","path":"active"}]}""")

        val ops = json.toScimPatchOps().getOrThrow()

        assertEquals(1, ops.size)
        assertEquals(ScimPatchOpType.ADD, ops[0].op)
        assertNull(ops[0].value)
    }

    @Test
    fun `PATCH op matching is deliberately case-insensitive`() {
        val json = Json.parseToJsonElement("""{"Operations":[{"op":"REPLACE","path":"active","value":true}]}""")

        val ops = json.toScimPatchOps().getOrThrow()

        assertEquals(ScimPatchOpType.REPLACE, ops[0].op)
    }

    /** Builds `{"nested":{"nested":...{"nested":"leaf"}...}}` with [depth] levels of nesting. */
    private fun nestedObjectJson(depth: Int): String {
        var json = "\"leaf\""
        repeat(depth) { json = """{"nested":$json}""" }
        return json
    }
}
