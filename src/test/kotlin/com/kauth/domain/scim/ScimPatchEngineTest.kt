package com.kauth.domain.scim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScimPatchEngineTest {
    private val engine = ScimPatchEngine()

    private fun member(id: String) = ScimValue.Complex(mapOf("value" to ScimValue.Str(id)))

    private fun group(vararg ids: String) =
        ScimResource(
            schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:Group"),
            attributes =
                mapOf(
                    "displayName" to ScimValue.Str("eng"),
                    "members" to ScimValue.MultiValued(ids.map { member(it) }),
                ),
        )

    private fun membersOf(r: ScimResource): List<String> =
        (r.attributes["members"] as ScimValue.MultiValued)
            .values
            .map { ((it as ScimValue.Complex).attributes["value"] as ScimValue.Str).value }

    private fun apply(
        r: ScimResource,
        vararg ops: ScimPatchOp,
    ) = engine.apply(r, ops.toList())

    @Test
    fun `add on a multi-valued attribute appends and keeps existing members`() {
        // Replacing instead of appending here silently drops every other member.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("3"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1", "2", "3"), membersOf(out))
    }

    @Test
    fun `remove with a valued path removes only the match`() {
        val out =
            apply(
                group("1", "2", "3"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("""members[value eq "2"]""").getOrThrow(), null),
            ).getOrThrow()

        assertEquals(listOf("1", "3"), membersOf(out))
    }

    @Test
    fun `remove with a plain path clears the attribute`() {
        val out =
            apply(group("1", "2"), ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("members").getOrThrow(), null))
                .getOrThrow()

        assertTrue(out.attributes["members"] == null || out.attributes["members"] == ScimValue.Null)
    }

    @Test
    fun `replace on a multi-valued attribute replaces the whole list`() {
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REPLACE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("9"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("9"), membersOf(out))
    }

    @Test
    fun `replace on a sub-attribute leaves siblings alone`() {
        val r =
            ScimResource(
                schemas = emptyList(),
                attributes =
                    mapOf(
                        "userName" to ScimValue.Str("ada"),
                        "name" to
                            ScimValue.Complex(
                                mapOf(
                                    "givenName" to ScimValue.Str("Ada"),
                                    "familyName" to ScimValue.Str("Lovelace"),
                                ),
                            ),
                    ),
            )
        val out =
            apply(
                r,
                ScimPatchOp(
                    ScimPatchOpType.REPLACE,
                    parsePath("name.givenName").getOrThrow(),
                    ScimValue.Str("Augusta"),
                ),
            ).getOrThrow()

        val name = out.attributes["name"] as ScimValue.Complex
        assertEquals(ScimValue.Str("Augusta"), name.attributes["givenName"])
        assertEquals(ScimValue.Str("Lovelace"), name.attributes["familyName"])
        assertEquals(ScimValue.Str("ada"), out.attributes["userName"])
    }

    @Test
    fun `a null path merges the value as a partial resource`() {
        // Entra sends this shape; it is legal per RFC 7644 for add and replace.
        val out =
            apply(
                group("1"),
                ScimPatchOp(ScimPatchOpType.REPLACE, null, ScimValue.Complex(mapOf("active" to ScimValue.Bool(false)))),
            ).getOrThrow()

        assertEquals(ScimValue.Bool(false), out.attributes["active"])
        assertEquals(listOf("1"), membersOf(out))
    }

    @Test
    fun `operations apply in order`() {
        val out =
            apply(
                group("1"),
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("2"))),
                ),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("""members[value eq "1"]""").getOrThrow(), null),
            ).getOrThrow()

        assertEquals(listOf("2"), membersOf(out))
    }

    @Test
    fun `a failure part-way through leaves the resource untouched`() {
        val original = group("1", "2")
        val result =
            apply(
                original,
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("3"))),
                ),
                ScimPatchOp(ScimPatchOpType.REPLACE, parsePath("nickName").getOrThrow(), ScimValue.Str("x")),
            )

        assertTrue(result.isFailure)
        assertEquals(listOf("1", "2"), membersOf(original))
    }

    @Test
    fun `an unknown attribute fails rather than silently doing nothing`() {
        // A silently ignored PATCH is a sync that appears to work and does not.
        val result =
            apply(
                group("1"),
                ScimPatchOp(ScimPatchOpType.REPLACE, parsePath("nickName").getOrThrow(), ScimValue.Str("x")),
            )

        assertEquals(ScimErrorType.invalidPath, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `remove with a valued path matching nothing is a no-op, not an error`() {
        val out =
            apply(
                group("1"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("""members[value eq "99"]""").getOrThrow(), null),
            ).getOrThrow()

        assertEquals(listOf("1"), membersOf(out))
    }
}
