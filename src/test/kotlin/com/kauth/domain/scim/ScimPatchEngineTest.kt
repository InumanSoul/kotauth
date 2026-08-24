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

    private fun email(address: String) =
        ScimValue.Complex(mapOf("value" to ScimValue.Str(address), "type" to ScimValue.Str("work")))

    private fun user(vararg emails: ScimValue) =
        ScimResource(
            schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
            attributes =
                mapOf(
                    "userName" to ScimValue.Str("ada"),
                    "emails" to ScimValue.MultiValued(emails.toList()),
                ),
        )

    private fun emailsOf(r: ScimResource): List<String> =
        (r.attributes["emails"] as ScimValue.MultiValued)
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
    fun `a pathless partial carrying an unstored core attribute merges instead of failing`() {
        // A pathless partial is a body fragment, judged the way a PUT body is: an attribute SCIM
        // defines and Kotauth does not persist rides along and is ignored. An explicit path naming
        // the same attribute stays a failure — see the test above — because that is a targeted
        // write, and answering 200 to one would be a lie.
        val out =
            apply(
                group("1"),
                ScimPatchOp(
                    ScimPatchOpType.REPLACE,
                    null,
                    ScimValue.Complex(
                        mapOf("displayName" to ScimValue.Str("sales"), "title" to ScimValue.Str("Engineer")),
                    ),
                ),
            ).getOrThrow()

        assertEquals(ScimValue.Str("sales"), out.attributes["displayName"])
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

    // -------------------------------------------------------------------------
    // `remove` carrying a `value` (RFC 7644 section 3.5.2.2) — remove the listed
    // entries, not the whole collection.
    // -------------------------------------------------------------------------

    @Test
    fun `remove with a plain path and a one-entry value removes only that member`() {
        val out =
            apply(
                group("1", "2", "3"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("2"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1", "3"), membersOf(out))
    }

    @Test
    fun `remove with a plain path and a two-entry value removes both and leaves the third`() {
        val out =
            apply(
                group("1", "2", "3"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("1"), member("3"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("2"), membersOf(out))
    }

    @Test
    fun `remove with a plain path and a bare object value removes that one member`() {
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("members").getOrThrow(), member("1")),
            ).getOrThrow()

        assertEquals(listOf("2"), membersOf(out))
    }

    @Test
    fun `remove with a plain path and a value naming every member leaves an empty collection`() {
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("1"), member("2"))),
                ),
            ).getOrThrow()

        assertEquals(ScimValue.MultiValued(emptyList()), out.attributes["members"])
    }

    @Test
    fun `remove with a plain path and a value naming an absent member leaves the collection alone`() {
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("99"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1", "2"), membersOf(out))
    }

    @Test
    fun `remove with a plain path and a value naming one present and one absent member removes the present one`() {
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("2"), member("99"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1"), membersOf(out))
    }

    @Test
    fun `remove matches a padded member id, the same one add stored`() {
        // ScimGroupMapper.parseMembers trims before parsing, so `add` of " 2 " puts user 2 in the
        // group. A remove comparing the raw wire string matches nothing and answers 200 while the
        // user keeps the group's roles — a deprovisioning miss reported as success.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member(" 2 "))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1"), membersOf(out))
    }

    @Test
    fun `remove matches a leading-zero member id`() {
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("002"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1"), membersOf(out))
    }

    @Test
    fun `remove matches a member added under a padded id`() {
        // The mirror-image pair end to end: the padded form goes in, the canonical form comes out.
        val added =
            apply(
                group("1"),
                ScimPatchOp(
                    ScimPatchOpType.ADD,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member(" 42 "))),
                ),
            ).getOrThrow()

        val out =
            apply(
                added,
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(member("42"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("1"), membersOf(out))
    }

    @Test
    fun `remove with an explicit null value is rejected rather than emptying the collection`() {
        // The decision behind this: an explicit null is a caller who sent the `value` member and
        // could not fill it, which is the least informative input there is. Reading it as "remove
        // everything" hands it the most destructive outcome, while a merely malformed value is
        // already a 400. Omitting `value` is still how a caller means "remove every entry".
        val result =
            apply(
                group("1", "2"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("members").getOrThrow(), ScimValue.Null),
            )

        assertEquals(ScimErrorType.invalidValue, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `an explicit null value stays a clear on replace, where the Null policy is unchanged`() {
        // Scoped to `remove`: a `replace`'s value IS the new state, and RFC 7644 §3.5.2.3 makes
        // null a meaningful one — the same way an attribute's null inside a resource clears it.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(ScimPatchOpType.REPLACE, parsePath("externalId").getOrThrow(), ScimValue.Null),
            ).getOrThrow()

        assertEquals(ScimValue.Null, out.attributes["externalId"])
    }

    @Test
    fun `an explicit null on replace still clears a multi-valued attribute`() {
        // The shape the refusal is deliberately NOT extended to: `replace` says "this is the new
        // state", and an empty one is a state. Only `remove` has an argument it could misread.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(ScimPatchOpType.REPLACE, parsePath("members").getOrThrow(), ScimValue.Null),
            ).getOrThrow()

        assertEquals(ScimValue.Null, out.attributes["members"])
    }

    @Test
    fun `an explicit null value on a valued-path remove still removes the match`() {
        // A valued path carries its own filter, so the `value` is never read and there was never
        // anything ambiguous to refuse. Rejecting it here is a deprovisioning miss.
        val out =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members[value eq \"2\"]").getOrThrow(),
                    ScimValue.Null,
                ),
            ).getOrThrow()

        assertEquals(listOf("1"), membersOf(out))
    }

    @Test
    fun `an explicit null value on a singular remove still clears the attribute`() {
        // externalId is the IdP-unlink flow ScimUserMapper's absent-attribute policy calls out; a
        // singular attribute has no entries to select between, so its `value` is never read.
        val withExternalId =
            group("1").let { it.copy(attributes = it.attributes + ("externalId" to ScimValue.Str("idp-1"))) }

        val out =
            apply(
                withExternalId,
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("externalId").getOrThrow(), ScimValue.Null),
            ).getOrThrow()

        assertTrue(out.attributes["externalId"] == null)
    }

    @Test
    fun `an explicit null value on a displayName remove still clears the attribute`() {
        val out =
            apply(
                group("1"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("displayName").getOrThrow(), ScimValue.Null),
            ).getOrThrow()

        assertTrue(out.attributes["displayName"] == null)
    }

    @Test
    fun `remove of emails matches a padded address, the same one the mapper stores`() {
        // The whitespace variant used to answer 200 with the address unchanged while the unpadded
        // form was a 400: same intent, two answers, and the padded one reported success.
        val out =
            apply(
                user(email("ada@example.com"), email("ada@work.example")),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("emails").getOrThrow(),
                    ScimValue.MultiValued(listOf(email(" ada@work.example "))),
                ),
            ).getOrThrow()

        assertEquals(listOf("ada@example.com"), emailsOf(out))
    }

    @Test
    fun `remove with a plain path and no value still clears the whole collection`() {
        // Regression guard on the reading that is already correct: no value means remove all.
        val out =
            apply(group("1", "2"), ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("members").getOrThrow(), null))
                .getOrThrow()

        assertTrue(out.attributes["members"] == null)
    }

    @Test
    fun `remove with a plain path and a bare string value is rejected rather than emptying the collection`() {
        val result =
            apply(
                group("1", "2"),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("members").getOrThrow(), ScimValue.Str("2")),
            )

        assertEquals(ScimErrorType.invalidValue, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `remove with a plain path and a value of bare strings is rejected rather than emptying the collection`() {
        val result =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(ScimValue.Str("2"))),
                ),
            )

        assertEquals(ScimErrorType.invalidValue, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `remove with a plain path and an entry with no value sub-attribute is rejected`() {
        val result =
            apply(
                group("1", "2"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("members").getOrThrow(),
                    ScimValue.MultiValued(listOf(ScimValue.Complex(mapOf("display" to ScimValue.Str("u2"))))),
                ),
            )

        assertEquals(ScimErrorType.invalidValue, (result.exceptionOrNull() as ScimFailure).type)
    }

    @Test
    fun `remove of emails with a value removes only the listed address`() {
        val out =
            apply(
                user(email("ada@example.com"), email("ada@old.example")),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("emails").getOrThrow(),
                    ScimValue.MultiValued(listOf(email("ada@old.example"))),
                ),
            ).getOrThrow()

        assertEquals(listOf("ada@example.com"), emailsOf(out))
    }

    @Test
    fun `remove of emails with no value still clears the whole collection`() {
        val out =
            apply(
                user(email("ada@example.com"), email("ada@old.example")),
                ScimPatchOp(ScimPatchOpType.REMOVE, parsePath("emails").getOrThrow(), null),
            ).getOrThrow()

        assertTrue(out.attributes["emails"] == null)
    }

    @Test
    fun `remove of emails with a value naming the last address leaves an empty collection`() {
        val out =
            apply(
                user(email("ada@example.com")),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("emails").getOrThrow(),
                    ScimValue.MultiValued(listOf(email("ada@example.com"))),
                ),
            ).getOrThrow()

        assertEquals(ScimValue.MultiValued(emptyList()), out.attributes["emails"])
    }

    @Test
    fun `remove of a singular attribute with a value still removes the attribute`() {
        val out =
            apply(
                group("1"),
                ScimPatchOp(
                    ScimPatchOpType.REMOVE,
                    parsePath("displayName").getOrThrow(),
                    ScimValue.Str("eng"),
                ),
            ).getOrThrow()

        assertTrue(out.attributes["displayName"] == null)
    }
}
