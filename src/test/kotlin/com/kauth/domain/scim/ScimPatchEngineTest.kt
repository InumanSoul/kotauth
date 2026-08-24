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
        // Some connectors send this shape; it is legal per RFC 7644 for add and replace.
        val out =
            apply(
                group("1"),
                ScimPatchOp(ScimPatchOpType.REPLACE, null, ScimValue.Complex(mapOf("active" to ScimValue.Bool(false)))),
            ).getOrThrow()

        assertEquals(ScimValue.Bool(false), out.attributes["active"])
        assertEquals(listOf("1"), membersOf(out))
    }

    @Test
    fun `a pathless add of members appends and every existing member survives`() {
        // RFC 7644 section 3.5.2.1: for `add`, a multi-valued attribute inside a pathless
        // partial gets a new value added, not the whole collection replaced. A connector
        // sending {"op":"add","value":{"members":[...]}} against a 400-member group must
        // not come back with just the one member it sent.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    null,
                    ScimValue.Complex(mapOf("members" to ScimValue.MultiValued(listOf(member("3"))))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1", "2", "3"), membersOf(out))
    }

    @Test
    fun `a pathless replace of members still replaces the whole collection`() {
        // REPLACE under a pathless op is a full-replace merge, unlike ADD — this is the
        // correct behaviour and must not regress alongside the ADD fix above.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REPLACE,
                    null,
                    ScimValue.Complex(mapOf("members" to ScimValue.MultiValued(listOf(member("9"))))),
                ),
            ).getOrThrow()

        assertEquals(listOf("9"), membersOf(out))
    }

    @Test
    fun `a pathless add of emails sets the address instead of hiding it behind the stored one`() {
        // Kotauth stores exactly one address and renders it as a single type:"work" entry, so an
        // appending `add` leaves ScimUserMapper.selectEmail picking the stored address back and
        // the request is a silent no-op. `add` therefore sets emails; the shape stays a collection.
        fun email(addr: String) = ScimValue.Complex(mapOf("value" to ScimValue.Str(addr)))
        val user =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes =
                    mapOf(
                        "userName" to ScimValue.Str("ada"),
                        "emails" to ScimValue.MultiValued(listOf(email("ada@old.example"))),
                    ),
            )
        val out =
            apply(
                user,
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    null,
                    ScimValue.Complex(mapOf("emails" to ScimValue.MultiValued(listOf(email("ada@new.example"))))),
                ),
            ).getOrThrow()

        val addresses =
            (out.attributes["emails"] as ScimValue.MultiValued)
                .values
                .map { ((it as ScimValue.Complex).attributes["value"] as ScimValue.Str).value }
        assertEquals(listOf("ada@new.example"), addresses)
    }

    @Test
    fun `a targeted add of emails also sets rather than appends`() {
        fun email(addr: String) = ScimValue.Complex(mapOf("value" to ScimValue.Str(addr)))
        val user =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes =
                    mapOf(
                        "userName" to ScimValue.Str("ada"),
                        "emails" to ScimValue.MultiValued(listOf(email("ada@old.example"))),
                    ),
            )
        val out =
            apply(
                user,
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    parsePath("emails").getOrThrow(),
                    ScimValue.MultiValued(listOf(email("ada@new.example"))),
                ),
            ).getOrThrow()

        val emails = out.attributes["emails"] as ScimValue.MultiValued
        assertEquals(1, emails.values.size)
        assertEquals(
            "ada@new.example",
            ((emails.values.single() as ScimValue.Complex).attributes["value"] as ScimValue.Str).value,
        )
    }

    private fun userWithName(
        givenName: String,
        familyName: String,
    ) = ScimResource(
        schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
        attributes =
            mapOf(
                "userName" to ScimValue.Str("ada"),
                "name" to
                    ScimValue.Complex(
                        mapOf(
                            "givenName" to ScimValue.Str(givenName),
                            "familyName" to ScimValue.Str(familyName),
                        ),
                    ),
            ),
    )

    @Test
    fun `a pathless add of one sub-attribute of a complex attribute preserves its siblings`() {
        // Same family of bug as the multi-valued append fix above, one attribute shape over:
        // {"op":"add","value":{"name":{"givenName":"Ada B."}}} must not wipe familyName.
        val out =
            apply(
                userWithName("Ada", "Lovelace"),
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    null,
                    ScimValue.Complex(
                        mapOf("name" to ScimValue.Complex(mapOf("givenName" to ScimValue.Str("Ada B.")))),
                    ),
                ),
            ).getOrThrow()

        val name = out.attributes["name"] as ScimValue.Complex
        assertEquals(ScimValue.Str("Ada B."), name.attributes["givenName"])
        assertEquals(ScimValue.Str("Lovelace"), name.attributes["familyName"])
    }

    @Test
    fun `characterization - a pathless add naming every sub-attribute reads the same merged or overwritten`() {
        // Not a guard: with both sub-attributes named, the sub-attribute merge and a plain
        // overwrite produce the same result, so this passes either way. The guard for the merge
        // is `a pathless add of one sub-attribute of a complex attribute preserves its siblings`.
        val out =
            apply(
                userWithName("Ada", "Lovelace"),
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    null,
                    ScimValue.Complex(
                        mapOf(
                            "name" to
                                ScimValue.Complex(
                                    mapOf(
                                        "givenName" to ScimValue.Str("Augusta"),
                                        "familyName" to ScimValue.Str("King"),
                                    ),
                                ),
                        ),
                    ),
                ),
            ).getOrThrow()

        val name = out.attributes["name"] as ScimValue.Complex
        assertEquals(ScimValue.Str("Augusta"), name.attributes["givenName"])
        assertEquals(ScimValue.Str("King"), name.attributes["familyName"])
    }

    @Test
    fun `a pathless replace of one sub-attribute of a complex attribute still clears its siblings`() {
        // REPLACE is a full-attribute overwrite, not a sub-attribute merge like ADD — this
        // must not regress alongside the ADD fix above.
        val out =
            apply(
                userWithName("Ada", "Lovelace"),
                ScimPatchOp(
                    ScimPatchOpType.REPLACE,
                    null,
                    ScimValue.Complex(
                        mapOf("name" to ScimValue.Complex(mapOf("givenName" to ScimValue.Str("Ada B.")))),
                    ),
                ),
            ).getOrThrow()

        val name = out.attributes["name"] as ScimValue.Complex
        assertEquals(ScimValue.Str("Ada B."), name.attributes["givenName"])
        assertEquals(null, name.attributes["familyName"])
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

    @Test
    fun `add of a bare complex to a multi-valued attribute still appends`() {
        // Regression guard for the "add overwrites unless already wrapped" bug: the append
        // decision must be driven by the existing attribute's shape, not the incoming value's.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(ScimPatchOpType.ADD, parsePath("members").getOrThrow(), member("3")),
            ).getOrThrow()

        assertEquals(listOf("1", "2", "3"), membersOf(out))
    }

    @Test
    fun `add of a multi-valued value to an existing singular attribute promotes it`() {
        val r = ScimResource(schemas = emptyList(), attributes = mapOf("displayName" to ScimValue.Str("eng")))
        val out =
            apply(
                r,
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    parsePath("displayName").getOrThrow(),
                    ScimValue.MultiValued(listOf(ScimValue.Str("extra"))),
                ),
            ).getOrThrow()

        assertEquals(
            ScimValue.MultiValued(listOf(ScimValue.Str("eng"), ScimValue.Str("extra"))),
            out.attributes["displayName"],
        )
    }

    @Test
    fun `remove with a valued path on a null attribute is a no-op`() {
        val r = ScimResource(schemas = emptyList(), attributes = mapOf("members" to ScimValue.Null))
        val out =
            apply(r, ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("""members[value eq "1"]""").getOrThrow(), null))
                .getOrThrow()

        assertEquals(ScimValue.Null, out.attributes["members"])
    }

    @Test
    fun `replace on a sub-attribute of a null parent succeeds`() {
        val r = ScimResource(schemas = emptyList(), attributes = mapOf("name" to ScimValue.Null))
        val out =
            apply(
                r,
                ScimPatchOp(ScimPatchOpType.REPLACE, parsePath("name.givenName").getOrThrow(), ScimValue.Str("Ada")),
            ).getOrThrow()

        val name = out.attributes["name"] as ScimValue.Complex
        assertEquals(ScimValue.Str("Ada"), name.attributes["givenName"])
    }

    @Test
    fun `a valued remove that empties the collection drops the key like a plain remove`() {
        val out =
            apply(
                group("1"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("""members[value eq "1"]""").getOrThrow(), null),
            ).getOrThrow()

        assertTrue(out.attributes["members"] == null)
    }

    @Test
    fun `an unknown sub-attribute fails rather than being silently dropped`() {
        val r =
            ScimResource(
                schemas = emptyList(),
                attributes = mapOf("name" to ScimValue.Complex(mapOf("givenName" to ScimValue.Str("Ada")))),
            )
        val result =
            apply(
                r,
                ScimPatchOp(ScimPatchOpType.REPLACE, parsePath("name.middleInitial").getOrThrow(), ScimValue.Str("L")),
            )

        assertEquals(ScimErrorType.invalidPath, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `add of a bare complex after a valued remove empties the collection stays multi-valued`() {
        // Regression guard: dropping the key when a valued remove empties a collection
        // must not make a later bare-value add store a scalar in its place.
        val out =
            apply(
                group("1"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("""members[value eq "1"]""").getOrThrow(), null),
                ScimPatchOp(ScimPatchOpType.ADD, parsePath("members").getOrThrow(), member("9")),
            ).getOrThrow()

        assertTrue(out.attributes["members"] is ScimValue.MultiValued)
        assertEquals(listOf("9"), membersOf(out))
    }

    @Test
    fun `an unknown member sub-attribute fails rather than being silently dropped`() {
        val result =
            apply(
                group("1"),
                ScimPatchOp(
                    ScimPatchOpType.REPLACE,
                    parsePath("""members[value eq "1"].dispaly""").getOrThrow(),
                    ScimValue.Str("x"),
                ),
            )

        assertEquals(ScimErrorType.invalidPath, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `password is a known attribute so a provisioned user can have one set`() {
        val user =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes = mapOf("userName" to ScimValue.Str("ada")),
            )
        val out =
            engine
                .apply(
                    user,
                    listOf(
                        ScimPatchOp(
                            ScimPatchOpType.REPLACE,
                            parsePath("password").getOrThrow(),
                            ScimValue.Str("s3cret"),
                        ),
                    ),
                ).getOrThrow()

        assertEquals(ScimValue.Str("s3cret"), out.attributes["password"])
    }

    @Test
    fun `groups is read-only and reports mutability rather than an unknown path`() {
        // RFC 7643 section 4.1.2: a user's group membership is written via the Groups
        // resource. Reporting invalidPath would tell an integrator the attribute does
        // not exist, sending them to debug the wrong thing.
        val user =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes = mapOf("userName" to ScimValue.Str("ada")),
            )
        val result =
            engine.apply(
                user,
                listOf(
                    ScimPatchOp(
                        ScimPatchOpType.ADD,
                        parsePath("groups").getOrThrow(),
                        ScimValue.MultiValued(listOf(ScimValue.Str("g1"))),
                    ),
                ),
            )

        assertEquals(ScimErrorType.mutability, (result.exceptionOrNull() as ScimFailure).type)
    }
}
