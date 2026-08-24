package com.kauth.domain.scim

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScimGroupMapperTest {
    private val tenantId = TenantId(1)

    private fun group() =
        Group(
            id = GroupId(3),
            tenantId = tenantId,
            name = "Engineering",
            externalId = "grp-ext-1",
            roleIds = listOf(RoleId(9)),
        )

    private fun str(
        r: ScimResource,
        k: String,
    ) = (r.attributes[k] as? ScimValue.Str)?.value

    private fun memberValues(r: ScimResource) =
        (r.attributes["members"] as ScimValue.MultiValued)
            .values
            .map { ((it as ScimValue.Complex).attributes["value"] as ScimValue.Str).value }

    private fun ScimResource.merged() = MergedScimResource.fromFullReplace(this)

    @Test
    fun `toResource emits displayName, externalId and members`() {
        val r = ScimGroupMapper.toResource(group(), listOf(UserId(1), UserId(2)))

        assertEquals("3", str(r, "id"))
        assertEquals("Engineering", str(r, "displayName"))
        assertEquals("grp-ext-1", str(r, "externalId"))
        assertEquals(listOf("1", "2"), memberValues(r))
        assertTrue(r.schemas.contains("urn:ietf:params:scim:schemas:core:2.0:Group"))
    }

    @Test
    fun `toResource never emits roles`() {
        // Roles are not part of the SCIM group model; emitting them would invite a
        // PUT to clear them.
        val r = ScimGroupMapper.toResource(group(), emptyList())
        assertNull(r.attributes["roles"])
        assertNull(r.attributes["roleIds"])
    }

    @Test
    fun `toResource emits an empty members array for a group with no members`() {
        val r = ScimGroupMapper.toResource(group(), emptyList())
        assertEquals(emptyList(), memberValues(r))
    }

    @Test
    fun `toDomain preserves existing role assignments`() {
        // A directory sync must never strip permissions. PUT clears absent attributes,
        // so roles surviving this call is the whole safeguard.
        val existing = group()
        val r = ScimGroupMapper.toResource(existing, emptyList())

        val write = ScimGroupMapper.toDomain(r.merged(), existing = existing, tenantId = tenantId).getOrThrow()

        assertEquals(listOf(RoleId(9)), write.group.roleIds)
    }

    @Test
    fun `toDomain parses member ids`() {
        val r = resourceWith(members = listOf("4", "5"))
        assertEquals(
            listOf(UserId(4), UserId(5)),
            ScimGroupMapper.toDomain(r.merged(), null, tenantId).getOrThrow().memberIds,
        )
    }

    @Test
    fun `toDomain rejects a nested group member instead of flattening it`() {
        val nested =
            ScimValue.MultiValued(
                listOf(
                    ScimValue.Complex(
                        mapOf(
                            "value" to ScimValue.Str("7"),
                            "type" to ScimValue.Str("Group"),
                        ),
                    ),
                ),
            )
        val r =
            ScimResource(
                schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:Group"),
                attributes = mapOf("displayName" to ScimValue.Str("Eng"), "members" to nested),
            )

        val failure = ScimGroupMapper.toDomain(r.merged(), null, tenantId).exceptionOrNull() as ScimFailure

        assertEquals(ScimErrorType.invalidValue, failure.type)
        assertTrue(failure.detail.contains("nested", ignoreCase = true))
    }

    @Test
    fun `toDomain rejects a member id that is not numeric`() {
        val r = resourceWith(members = listOf("not-an-id"))
        val failure = ScimGroupMapper.toDomain(r.merged(), null, tenantId).exceptionOrNull() as ScimFailure

        assertEquals(ScimErrorType.invalidValue, failure.type)
    }

    @Test
    fun `toDomain requires displayName`() {
        val r = ScimResource(schemas = emptyList(), attributes = mapOf("externalId" to ScimValue.Str("x")))
        val failure = ScimGroupMapper.toDomain(r.merged(), null, tenantId).exceptionOrNull() as ScimFailure

        assertEquals(ScimErrorType.invalidValue, failure.type)
    }

    @Test
    fun `toDomain trims but does not lower-case externalId`() {
        val r = resourceWith(members = emptyList(), externalId = "  Grp-EXT-2  ")
        assertEquals(
            "Grp-EXT-2",
            ScimGroupMapper
                .toDomain(r.merged(), null, tenantId)
                .getOrThrow()
                .group.externalId,
        )
    }

    @Test
    fun `round trip preserves displayName, externalId and members`() {
        val original = group()
        val members = listOf(UserId(1), UserId(2))
        val write =
            ScimGroupMapper
                .toDomain(ScimGroupMapper.toResource(original, members).merged(), original, tenantId)
                .getOrThrow()

        assertEquals(original.name, write.group.name)
        assertEquals(original.externalId, write.group.externalId)
        assertEquals(members, write.memberIds)
    }

    private fun resourceWith(
        members: List<String>,
        externalId: String? = null,
    ): ScimResource {
        val attrs =
            buildMap<String, ScimValue> {
                put("displayName", ScimValue.Str("Eng"))
                if (externalId != null) put("externalId", ScimValue.Str(externalId))
                put(
                    "members",
                    ScimValue.MultiValued(members.map { ScimValue.Complex(mapOf("value" to ScimValue.Str(it))) }),
                )
            }
        return ScimResource(schemas = listOf("urn:ietf:params:scim:schemas:core:2.0:Group"), attributes = attrs)
    }
}
