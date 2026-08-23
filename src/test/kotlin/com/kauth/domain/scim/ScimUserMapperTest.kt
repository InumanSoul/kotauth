package com.kauth.domain.scim

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

        val write = ScimUserMapper.toDomain(r, existing = null, tenantId = tenantId).getOrThrow()

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
                .toDomain(r, null, tenantId)
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
                .toDomain(r, null, tenantId)
                .getOrThrow()
                .user.fullName,
        )
    }

    @Test
    fun `toDomain rejects a composed fullName that exceeds the column`() {
        // full_name is varchar(255); two long parts can exceed it even though each fits.
        val r = resourceWithName(given = "g".repeat(200), family = "f".repeat(200))
        val failure = ScimUserMapper.toDomain(r, null, tenantId).exceptionOrNull() as ScimFailure

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
                .toDomain(r, null, tenantId)
                .getOrThrow()
                .user.externalId,
        )
    }

    @Test
    fun `toDomain preserves the existing password hash on update`() {
        val existing = user().copy(passwordHash = "a-real-hash")
        val r = ScimUserMapper.toResource(existing)

        val write = ScimUserMapper.toDomain(r, existing = existing, tenantId = tenantId).getOrThrow()

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

        val write = ScimUserMapper.toDomain(r, null, tenantId).getOrThrow()

        assertEquals("s3cret", write.plaintextPassword)
        // The mapper does not hash — that is the service's job, with its policy checks.
        assertEquals(User.SENTINEL_PASSWORD_HASH, write.user.passwordHash)
    }

    @Test
    fun `toDomain requires userName`() {
        val r = ScimResource(schemas = emptyList(), attributes = mapOf("externalId" to ScimValue.Str("x")))
        val failure = ScimUserMapper.toDomain(r, null, tenantId).exceptionOrNull() as ScimFailure

        assertEquals(ScimErrorType.invalidValue, failure.type)
    }

    @Test
    fun `round trip preserves every mapped attribute`() {
        val original = user()
        val back = ScimUserMapper.toDomain(ScimUserMapper.toResource(original), original, tenantId).getOrThrow().user

        assertEquals(original.username, back.username)
        assertEquals(original.email, back.email)
        assertEquals(original.externalId, back.externalId)
        assertEquals(original.givenName, back.givenName)
        assertEquals(original.familyName, back.familyName)
        assertEquals(original.fullName, back.fullName)
        assertEquals(original.enabled, back.enabled)
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
