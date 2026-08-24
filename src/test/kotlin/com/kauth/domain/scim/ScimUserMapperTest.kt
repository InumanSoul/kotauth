package com.kauth.domain.scim

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScimUserMapperTest {
    private val tenantId = TenantId(1)

    private fun user() =
        User(
            id = UserId(7),
            tenantId = tenantId,
            username = "ada@example.com",
            email = "ada@example.com",
            fullName = "Ada Lovelace",
            passwordHash = User.SENTINEL_PASSWORD_HASH,
            externalId = "ext-1",
            givenName = "Ada",
            familyName = "Lovelace",
            enabled = true,
        )

    private fun str(
        r: ScimResource,
        k: String,
    ) = (r.attributes[k] as? ScimValue.Str)?.value

    // Every fixture below is already a complete representation (a synthetic PUT/POST body, or
    // toResource(existing)), so this is the fromFullReplace case — never a real merge step.
    private fun ScimResource.merged() = MergedScimResource.fromFullReplace(this)

    @Test
    fun `toResource emits the core attributes`() {
        val r = ScimUserMapper.toResource(user())

        assertEquals("7", str(r, "id"))
        assertEquals("ada@example.com", str(r, "userName"))
        assertEquals("ext-1", str(r, "externalId"))
        assertEquals("Ada Lovelace", str(r, "displayName"))
        assertEquals(ScimValue.Bool(true), r.attributes["active"])
        assertTrue(r.schemas.contains("urn:ietf:params:scim:schemas:core:2.0:User"))
    }

    @Test
    fun `toResource always emits meta resourceType, even without a location`() {
        val r = ScimUserMapper.toResource(user())
        val meta = r.attributes["meta"] as ScimValue.Complex

        assertEquals(ScimValue.Str("User"), meta.attributes["resourceType"])
        assertNull(meta.attributes["location"])
    }

    @Test
    fun `toResource emits meta location when given one`() {
        val r = ScimUserMapper.toResource(user(), location = "https://kotauth.example/t/acme/scim/v2/Users/7")
        val meta = r.attributes["meta"] as ScimValue.Complex

        assertEquals(
            ScimValue.Str("https://kotauth.example/t/acme/scim/v2/Users/7"),
            meta.attributes["location"],
        )
    }

    @Test
    fun `toResource never emits meta lastModified — there is no update-tracking timestamp to source it from`() {
        val r = ScimUserMapper.toResource(user())
        val meta = r.attributes["meta"] as ScimValue.Complex

        assertNull(meta.attributes["lastModified"])
    }

    @Test
    fun `toResource populates groups from the caller-supplied membership list`() {
        val group = Group(id = GroupId(9), tenantId = tenantId, name = "Engineering")
        val r = ScimUserMapper.toResource(user(), groups = listOf(group))

        val groups = r.attributes["groups"] as ScimValue.MultiValued
        val entry = groups.values.single() as ScimValue.Complex
        assertEquals(ScimValue.Str("9"), entry.attributes["value"])
        assertEquals(ScimValue.Str("Engineering"), entry.attributes["display"])
    }

    @Test
    fun `toResource omits groups entirely — not an empty array — when there are none`() {
        val r = ScimUserMapper.toResource(user())
        assertNull(r.attributes["groups"])
    }

    @Test
    fun `toResource omits name entirely when both parts are null`() {
        // Never reverse-engineer name parts by splitting fullName — an honest gap
        // beats a fabricated value, and splitting is wrong for many names.
        val r = ScimUserMapper.toResource(user().copy(givenName = null, familyName = null))

        assertNull(r.attributes["name"])
        assertEquals("Ada Lovelace", str(r, "displayName"))
    }

    @Test
    fun `toResource never emits password`() {
        val r = ScimUserMapper.toResource(user().copy(passwordHash = "a-real-hash"))
        assertNull(r.attributes["password"])
    }

    @Test
    fun `toResource emits the work email as a multi-valued attribute`() {
        val r = ScimUserMapper.toResource(user())
        val emails = r.attributes["emails"] as ScimValue.MultiValued
        val first = emails.values.first() as ScimValue.Complex

        assertEquals(ScimValue.Str("ada@example.com"), first.attributes["value"])
        assertEquals(ScimValue.Str("work"), first.attributes["type"])
    }

    @Test
    fun `toDomain creates a user with the sentinel hash when no password is supplied`() {
        val r =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes =
                    mapOf(
                        "userName" to ScimValue.Str("grace@example.com"),
                        "externalId" to ScimValue.Str("ext-2"),
                        "name" to
                            ScimValue.Complex(
                                mapOf(
                                    "givenName" to ScimValue.Str("Grace"),
                                    "familyName" to ScimValue.Str("Hopper"),
                                ),
                            ),
                        "emails" to
                            ScimValue.MultiValued(
                                listOf(
                                    ScimValue.Complex(
                                        mapOf(
                                            "value" to ScimValue.Str("grace@example.com"),
                                            "type" to ScimValue.Str("work"),
                                        ),
                                    ),
                                ),
                            ),
                    ),
            )

        val write = ScimUserMapper.toDomain(r.merged(), existing = null, tenantId = tenantId).getOrThrow()

        assertEquals("grace@example.com", write.user.username)
        assertEquals("ext-2", write.user.externalId)
        assertEquals("Grace", write.user.givenName)
        assertEquals("Grace Hopper", write.user.fullName)
        assertEquals(User.SENTINEL_PASSWORD_HASH, write.user.passwordHash)
        assertNull(write.plaintextPassword)
    }

    @Test
    fun `toDomain composes fullName from the parts it was given`() {
        val r = resourceWithName(given = "Grace", family = null)
        assertEquals(
            "Grace",
            ScimUserMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .user.fullName,
        )
    }

    @Test
    fun `an explicit displayName wins over the composed value`() {
        val r =
            resourceWithName(given = "Grace", family = "Hopper")
                .let { it.copy(attributes = it.attributes + ("displayName" to ScimValue.Str("Admiral Hopper"))) }

        assertEquals(
            "Admiral Hopper",
            ScimUserMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .user.fullName,
        )
    }

    @Test
    fun `an empty displayName falls through to the composed name`() {
        // A cleared field often serialises as "" rather than being omitted; it must not
        // win over a real composed name.
        val r =
            resourceWithName(given = "Grace", family = "Hopper")
                .let { it.copy(attributes = it.attributes + ("displayName" to ScimValue.Str(""))) }

        assertEquals(
            "Grace Hopper",
            ScimUserMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .user.fullName,
        )
    }

    @Test
    fun `an empty displayName on update preserves the existing fullName`() {
        val existing = user()
        val r =
            resourceWithName(given = null, family = null)
                .let { it.copy(attributes = it.attributes + ("displayName" to ScimValue.Str("  "))) }

        val write = ScimUserMapper.toDomain(r.merged(), existing, tenantId).getOrThrow()

        assertEquals(existing.fullName, write.user.fullName)
    }

    @Test
    fun `an empty work email on update preserves the existing email`() {
        // Same emptiness guard as displayName: an update must not blank a login-relevant
        // address just because the IdP sent an empty value instead of omitting it.
        val existing = user()
        val r =
            resourceWithName(given = "A", family = "B")
                .let {
                    it.copy(
                        attributes =
                            it.attributes +
                                (
                                    "emails" to
                                        ScimValue.MultiValued(
                                            listOf(
                                                ScimValue.Complex(
                                                    mapOf(
                                                        "value" to ScimValue.Str(""),
                                                        "type" to ScimValue.Str("work"),
                                                    ),
                                                ),
                                            ),
                                        )
                                ),
                    )
                }

        val write = ScimUserMapper.toDomain(r.merged(), existing, tenantId).getOrThrow()

        assertEquals(existing.email, write.user.email)
    }

    @Test
    fun `a whitespace-only work email on update preserves the existing email`() {
        val existing = user()
        val r =
            resourceWithName(given = "A", family = "B")
                .let {
                    it.copy(
                        attributes =
                            it.attributes +
                                (
                                    "emails" to
                                        ScimValue.MultiValued(
                                            listOf(
                                                ScimValue.Complex(
                                                    mapOf(
                                                        "value" to ScimValue.Str("   "),
                                                        "type" to ScimValue.Str("work"),
                                                    ),
                                                ),
                                            ),
                                        )
                                ),
                    )
                }

        val write = ScimUserMapper.toDomain(r.merged(), existing, tenantId).getOrThrow()

        assertEquals(existing.email, write.user.email)
    }

    @Test
    fun `toDomain falls back to primary when no email is typed work`() {
        val r =
            resourceWithName("A", "B").let {
                it.copy(
                    attributes =
                        it.attributes +
                            (
                                "emails" to
                                    ScimValue.MultiValued(
                                        listOf(
                                            ScimValue.Complex(mapOf("value" to ScimValue.Str("alt@example.com"))),
                                            ScimValue.Complex(
                                                mapOf(
                                                    "value" to ScimValue.Str("primary@example.com"),
                                                    "primary" to ScimValue.Bool(true),
                                                ),
                                            ),
                                        ),
                                    )
                            ),
                )
            }

        assertEquals(
            "primary@example.com",
            ScimUserMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .user.email,
        )
    }

    @Test
    fun `toDomain falls back to the first email when neither work nor primary is present`() {
        // The common connector shape: a single email with no type and no primary flag.
        // Requiring an exact type == "work" match used to blank this out entirely.
        val r =
            resourceWithName("A", "B").let {
                it.copy(
                    attributes =
                        it.attributes +
                            (
                                "emails" to
                                    ScimValue.MultiValued(
                                        listOf(ScimValue.Complex(mapOf("value" to ScimValue.Str("only@example.com")))),
                                    )
                            ),
                )
            }

        assertEquals(
            "only@example.com",
            ScimUserMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .user.email,
        )
    }

    @Test
    fun `toDomain create with an untyped email does not misreport a missing email`() {
        // Regression: before the fallback chain, this shape produced email = "" and the
        // service rejected with "A valid email address is required.", pointing the
        // integrator at the wrong field.
        val r =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes =
                    mapOf(
                        "userName" to ScimValue.Str("noType@example.com"),
                        "emails" to
                            ScimValue.MultiValued(
                                listOf(ScimValue.Complex(mapOf("value" to ScimValue.Str("noType@example.com")))),
                            ),
                    ),
            )

        val write = ScimUserMapper.toDomain(r.merged(), existing = null, tenantId = tenantId).getOrThrow()
        assertEquals("noType@example.com", write.user.email)
    }

    @Test
    fun `toDomain work email wins even when it is not the first entry`() {
        val r =
            resourceWithName("A", "B").let {
                it.copy(
                    attributes =
                        it.attributes +
                            (
                                "emails" to
                                    ScimValue.MultiValued(
                                        listOf(
                                            ScimValue.Complex(
                                                mapOf(
                                                    "value" to ScimValue.Str("home@example.com"),
                                                    "type" to ScimValue.Str("home"),
                                                    "primary" to ScimValue.Bool(true),
                                                ),
                                            ),
                                            ScimValue.Complex(
                                                mapOf(
                                                    "value" to ScimValue.Str("work@example.com"),
                                                    "type" to ScimValue.Str("work"),
                                                ),
                                            ),
                                        ),
                                    )
                            ),
                )
            }

        assertEquals(
            "work@example.com",
            ScimUserMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .user.email,
        )
    }

    @Test
    fun `toDomain on update with untyped emails still updates the address, not silently keeps the old one`() {
        val existing = user()
        val r =
            resourceWithName(given = "A", family = "B").let {
                it.copy(
                    attributes =
                        it.attributes +
                            (
                                "emails" to
                                    ScimValue.MultiValued(
                                        listOf(ScimValue.Complex(mapOf("value" to ScimValue.Str("new@example.com")))),
                                    )
                            ),
                )
            }

        val write = ScimUserMapper.toDomain(r.merged(), existing, tenantId).getOrThrow()
        assertEquals("new@example.com", write.user.email)
    }

    @Test
    fun `toDomain on update clears externalId when it is omitted from a PUT`() {
        // PUT is a full replace: an IdP unlinking a user by dropping externalId must not
        // leave the stale correlation key in place forever.
        val existing = user()
        val r = resourceWithName(given = "A", family = "B")

        val write = ScimUserMapper.toDomain(r.merged(), existing, tenantId).getOrThrow()
        assertNull(write.user.externalId)
    }

    @Test
    fun `toDomain rejects a composed fullName that exceeds the column`() {
        // full_name is varchar(255); two long parts can exceed it even though each fits.
        val r = resourceWithName(given = "g".repeat(200), family = "f".repeat(200))
        val failure = ScimUserMapper.toDomain(r.merged(), null, tenantId).exceptionOrNull() as ScimFailure

        assertEquals(ScimErrorType.invalidValue, failure.type)
    }

    @Test
    fun `toDomain trims whitespace from externalId`() {
        // An IdP key is opaque and case-sensitive, so it is never lower-cased —
        // but surrounding whitespace is never meaningful and creates duplicates
        // the unique index cannot see.
        val r =
            resourceWithName("A", "B").let {
                it.copy(
                    attributes =
                        it.attributes + ("externalId" to ScimValue.Str("  ext-3  ")),
                )
            }

        assertEquals(
            "ext-3",
            ScimUserMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .user.externalId,
        )
    }

    @Test
    fun `toDomain preserves the existing password hash on update`() {
        val existing = user().copy(passwordHash = "a-real-hash")
        val r = ScimUserMapper.toResource(existing)

        val write = ScimUserMapper.toDomain(r.merged(), existing = existing, tenantId = tenantId).getOrThrow()

        assertEquals("a-real-hash", write.user.passwordHash)
        assertEquals(UserId(7), write.user.id)
    }

    @Test
    fun `toDomain surfaces a supplied password separately and never in the user`() {
        val r =
            resourceWithName("A", "B").let {
                it.copy(
                    attributes =
                        it.attributes + ("password" to ScimValue.Str("s3cret")),
                )
            }

        val write = ScimUserMapper.toDomain(r.merged(), null, tenantId).getOrThrow()

        assertEquals("s3cret", write.plaintextPassword)
        // The mapper does not hash — that is the service's job, with its policy checks.
        assertEquals(User.SENTINEL_PASSWORD_HASH, write.user.passwordHash)
    }

    @Test
    fun `toDomain requires userName`() {
        val r = ScimResource(schemas = emptyList(), attributes = mapOf("externalId" to ScimValue.Str("x")))
        val failure = ScimUserMapper.toDomain(r.merged(), null, tenantId).exceptionOrNull() as ScimFailure

        assertEquals(ScimErrorType.invalidValue, failure.type)
    }

    @Test
    fun `round trip preserves every mapped attribute`() {
        val original = user()
        val back =
            ScimUserMapper
                .toDomain(
                    ScimUserMapper.toResource(original).merged(),
                    original,
                    tenantId,
                ).getOrThrow()
                .user

        assertEquals(original.id, back.id)
        assertEquals(original.username, back.username)
        assertEquals(original.email, back.email)
        assertEquals(original.externalId, back.externalId)
        assertEquals(original.givenName, back.givenName)
        assertEquals(original.familyName, back.familyName)
        assertEquals(original.fullName, back.fullName)
        assertEquals(original.enabled, back.enabled)
    }

    // -------------------------------------------------------------------------
    // Attribute shapes — one table, checked once at the entry point
    // -------------------------------------------------------------------------

    private fun userResourceWith(vararg extra: Pair<String, ScimValue>) =
        ScimResource(
            schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
            attributes = mapOf("userName" to ScimValue.Str("ada@example.com")) + extra,
        )

    private fun shapeFailureOf(
        resource: ScimResource,
        existing: User?,
    ) = ScimUserMapper.toDomain(resource.merged(), existing, tenantId).exceptionOrNull() as ScimFailure

    @Test
    fun `a string active is rejected, not a silent failed deprovision`() {
        // "active":"false" is real connector behaviour and the tempting fix is to coerce it. A
        // coerced-away deprovision leaves the account authenticating while the IdP records it as
        // disabled; a 400 is the operator's cue to fix the mapping. Leniency belongs in a dialect
        // layer, never in this mapper.
        val existing = user()
        val failure = shapeFailureOf(userResourceWith("active" to ScimValue.Str("false")), existing)

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("active"), failure.detail)
        assertTrue(failure.detail.contains("a boolean"), failure.detail)
    }

    @Test
    fun `a bare-string emails value is rejected instead of keeping the stored address`() {
        val existing = user()
        val failure = shapeFailureOf(userResourceWith("emails" to ScimValue.Str("new@example.com")), existing)

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("emails"), failure.detail)
    }

    @Test
    fun `an array of bare-string emails is rejected, the same answer Groups gives that shape`() {
        val existing = user()
        val emails = ScimValue.MultiValued(listOf(ScimValue.Str("new@example.com")))
        val failure = shapeFailureOf(userResourceWith("emails" to emails), existing)

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("emails[0]"), failure.detail)
    }

    @Test
    fun `a numeric externalId is rejected instead of erasing the stored correlation key`() {
        val existing = user()
        val failure = shapeFailureOf(userResourceWith("externalId" to ScimValue.Num(9182)), existing)

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("externalId"), failure.detail)
    }

    @Test
    fun `an array displayName is rejected instead of echoing the stored name back`() {
        val existing = user()
        val displayName = ScimValue.MultiValued(listOf(ScimValue.Str("Ada L")))
        val failure = shapeFailureOf(userResourceWith("displayName" to displayName), existing)

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("displayName"), failure.detail)
    }

    @Test
    fun `an externalId longer than the column is rejected instead of reaching Postgres`() {
        val failure = shapeFailureOf(userResourceWith("externalId" to ScimValue.Str("e".repeat(256))), user())

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("externalId"), failure.detail)
    }

    @Test
    fun `a scalar userName is rejected rather than read as absent`() {
        val r =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
                attributes = mapOf("userName" to ScimValue.Bool(true)),
            )
        val failure = shapeFailureOf(r, user())

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("userName"), failure.detail)
    }

    @Test
    fun `a numeric givenName is rejected instead of erasing the stored one`() {
        // `name` is a Complex, so the top-level shape check passes and the sub-attribute cast used
        // to yield null — writing givenName = null over "Ada" under a 200 OK.
        val name = ScimValue.Complex(mapOf("givenName" to ScimValue.Num(123)))
        val failure = shapeFailureOf(userResourceWith("name" to name), user())

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("name.givenName"), failure.detail)
        assertTrue(failure.detail.contains("a string"), failure.detail)
    }

    @Test
    fun `a numeric emails value is rejected instead of echoing the stored address back`() {
        val emails =
            ScimValue.MultiValued(
                listOf(
                    ScimValue.Complex(
                        mapOf("value" to ScimValue.Num(123), "type" to ScimValue.Str("work")),
                    ),
                ),
            )
        val failure = shapeFailureOf(userResourceWith("emails" to emails), user())

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("emails[0].value"), failure.detail)
    }

    @Test
    fun `a shape failure inside an array names the offending index`() {
        val emails =
            ScimValue.MultiValued(
                listOf(
                    ScimValue.Complex(mapOf("value" to ScimValue.Str("ada@example.com"))),
                    ScimValue.Complex(
                        mapOf("value" to ScimValue.Str("ada@x.example"), "primary" to ScimValue.Str("yes")),
                    ),
                ),
            )
        val failure = shapeFailureOf(userResourceWith("emails" to emails), user())

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("emails[1].primary"), failure.detail)
        assertTrue(failure.detail.contains("a boolean"), failure.detail)
    }

    @Test
    fun `a sub-attribute this implementation does not read is left alone`() {
        // Unknown sub-attributes stay unchecked: RFC 7643 defines several `name` parts Kotauth
        // does not store, and rejecting them would drop records over data nobody reads.
        val name =
            ScimValue.Complex(
                mapOf("givenName" to ScimValue.Str("Ada"), "honorificPrefix" to ScimValue.Num(7)),
            )
        val write = ScimUserMapper.toDomain(userResourceWith("name" to name).merged(), user(), tenantId)

        assertEquals("Ada", write.getOrThrow().user.givenName)
    }

    @Test
    fun `an RFC 7643 attribute Kotauth does not store is accepted and ignored`() {
        val write =
            ScimUserMapper.toDomain(
                userResourceWith(
                    "title" to ScimValue.Str("Engineer"),
                    "phoneNumbers" to ScimValue.MultiValued(emptyList()),
                ).merged(),
                user(),
                tenantId,
            )

        assertEquals("ada@example.com", write.getOrThrow().user.username)
    }

    @Test
    fun `a schema-URN-keyed extension object is accepted and ignored`() {
        val extension =
            ScimValue.Complex(mapOf("department" to ScimValue.Str("R&D")))
        val write =
            ScimUserMapper.toDomain(
                userResourceWith(
                    "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User" to extension,
                ).merged(),
                user(),
                tenantId,
            )

        assertEquals("ada@example.com", write.getOrThrow().user.username)
    }

    @Test
    fun `a misspelled attribute is still rejected`() {
        val failure = shapeFailureOf(userResourceWith("emailz" to ScimValue.Str("x@example.com")), user())

        assertEquals(ScimErrorType.invalidSyntax, failure.type)
        assertTrue(failure.detail.contains("emailz"), failure.detail)
    }

    private fun resourceWithName(
        given: String?,
        family: String?,
    ): ScimResource {
        val nameAttrs =
            buildMap<String, ScimValue> {
                if (given != null) put("givenName", ScimValue.Str(given))
                if (family != null) put("familyName", ScimValue.Str(family))
            }
        return ScimResource(
            schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:User"),
            attributes =
                mapOf(
                    "userName" to ScimValue.Str("x@example.com"),
                    "name" to ScimValue.Complex(nameAttrs),
                ),
        )
    }
}
