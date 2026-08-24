package com.kauth.domain.scim

import com.kauth.domain.model.Group
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId

private const val GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group"

// groups.name is VARCHAR(100) NOT NULL; a check here turns an overlong name into a clean
// invalidValue response instead of a raw DB error at insert time.
private const val GROUP_NAME_MAX_LENGTH = 100

/**
 * The result of mapping an inbound SCIM group resource onto the domain. [memberIds] is carried
 * separately from [group] — membership lives in a join table (`user_groups`), not a [Group]
 * column, so the caller applies it as its own write rather than through a `Group` field.
 */
data class ScimGroupWrite(
    val group: Group,
    val memberIds: List<UserId>,
)

/**
 * Translates between the SCIM Group schema (RFC 7644 §3.2) and the domain [Group]. Pure mapping:
 * no I/O.
 *
 * [Group.roleIds] is never read from or written by this mapper, in either direction. Roles are
 * managed in the admin UI only; if SCIM could set them, a routine directory sync (PUT, which
 * clears any absent attribute) would silently strip permissions from every member of the group.
 */
object ScimGroupMapper {
    /** RFC 7643 §4.2's canonical value marking a member as another group rather than a user. */
    private const val MEMBER_TYPE_GROUP = "Group"

    /**
     * [location] is the absolute URL of this resource (RFC 7644 §3.1) — the caller resolves it
     * from the request, since this mapper does no I/O and doesn't know the base URL. Passing
     * `null` (the merge-baseline use inside `ScimPatchEngine`'s PATCH handling, or a filter-scan
     * match check, never a client-facing response) omits `meta.location`.
     */
    fun toResource(
        group: Group,
        memberIds: List<UserId>,
        location: String? = null,
    ): ScimResource {
        val attributes = mutableMapOf<String, ScimValue>()

        group.id?.let { attributes["id"] = ScimValue.Str(it.value.toString()) }
        attributes["displayName"] = ScimValue.Str(group.name)
        group.externalId?.let { attributes["externalId"] = ScimValue.Str(it) }
        attributes["meta"] =
            ScimValue.Complex(
                buildMap {
                    put("resourceType", ScimValue.Str("Group"))
                    location?.let { put("location", ScimValue.Str(it)) }
                },
            )

        // Always emitted, even when empty: an absent `members` array would be ambiguous with
        // "membership not loaded", where an empty array unambiguously means "no members".
        attributes["members"] =
            ScimValue.MultiValued(
                memberIds.map { id ->
                    ScimValue.Complex(
                        mapOf(
                            "value" to ScimValue.Str(id.value.toString()),
                            "type" to ScimValue.Str("User"),
                        ),
                    )
                },
            )

        // roleIds is deliberately never emitted here — see the class KDoc.
        return ScimResource(schemas = listOf(GROUP_SCHEMA), attributes = attributes)
    }

    /**
     * [merged] must wrap a fully merged representation — the complete desired state, not a bare
     * PATCH body. A PATCH route must call [ScimPatchEngine.applyMerged] against
     * [ScimGroupMapper.toResource] of the current group — that is the only way to obtain a
     * [MergedScimResource] for the PATCH case; there is no public factory that builds one from an
     * arbitrary resource. See [MergedScimResource]'s KDoc for why the contract exists at all.
     *
     * **Absent-attribute policy (PUT is a full replace, RFC 7644 §3.5.1):** `groups.name` is a
     * `NOT NULL` column, so it cannot represent "clear" — an omitted `displayName` falls back to
     * [existing]'s name, and on create (no [existing]) is rejected. `externalId` is nullable, so
     * an omitted one clears it, same as [ScimUserMapper.toDomain] — an IdP unlinking a group by
     * dropping `externalId` must not leave the stale correlation key in place. `members` follows
     * the same "absent clears" rule: an omitted array maps to no members.
     *
     * [Group.description], [Group.parentGroupId], [Group.attributes] and [Group.roleIds] have no
     * SCIM attribute at all and are always carried through from [existing] untouched.
     *
     * A member with `type: "Group"` is rejected outright rather than flattened: SCIM permits
     * nested group members, but Kotauth models nesting as a single [Group.parentGroupId], a
     * different shape entirely. Silently flattening a nested membership into a direct one would
     * grant users access the IdP never intended to grant directly.
     */
    fun toDomain(
        merged: MergedScimResource,
        existing: Group?,
        tenantId: TenantId,
    ): Result<ScimGroupWrite> {
        val resource = merged.value

        // A blank displayName ("" or whitespace) is a common connector shape for a cleared
        // field; treated as absent so it falls through to existing rather than storing "".
        val explicitName =
            (resource.attributes["displayName"] as? ScimValue.Str)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val name =
            explicitName ?: existing?.name
                ?: return Result.failure(ScimFailure(ScimErrorType.invalidValue, "displayName is required"))
        if (name.length > GROUP_NAME_MAX_LENGTH) {
            return Result.failure(
                ScimFailure(ScimErrorType.invalidValue, "displayName exceeds $GROUP_NAME_MAX_LENGTH characters"),
            )
        }

        // externalId is an opaque IdP key: trimmed because surrounding whitespace is never
        // meaningful, but never lower-cased because case may be significant to the IdP.
        val externalId =
            (resource.attributes["externalId"] as? ScimValue.Str)
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val memberIds =
            resource.parseMembers().getOrElse { return Result.failure(it) }

        val base =
            existing ?: Group(tenantId = tenantId, name = name)

        val group =
            base.copy(
                tenantId = existing?.tenantId ?: tenantId,
                name = name,
                externalId = externalId,
                // roleIds is never touched by SCIM — see the class KDoc.
            )

        return Result.success(ScimGroupWrite(group, memberIds))
    }

    private fun ScimResource.parseMembers(): Result<List<UserId>> {
        val raw = attributes["members"]
        // Absent, or explicitly cleared, means "no members" — the absent-clears rule in the KDoc
        // above. Present but not an array is a caller mistake, and reading it as "no members" is
        // how `{"members":"7"}` or `{"members":{"value":"7"}}` returns 200 and empties the group.
        if (raw == null || raw == ScimValue.Null) return Result.success(emptyList())
        val entries =
            (raw as? ScimValue.MultiValued)?.values
                ?: return Result.failure(
                    ScimFailure(
                        ScimErrorType.invalidValue,
                        "members must be an array of objects, got ${raw.shapeName()}",
                    ),
                )

        val memberIds = mutableListOf<UserId>()
        for (rawEntry in entries) {
            // RFC 7643 §4.2 defines "members" as multi-valued *complex*, with "value" as a
            // sub-attribute — a bare string id is not a legal member entry. Dropping it
            // silently is how a `replace` of plain ids has emptied a group; reject instead.
            val entry =
                rawEntry as? ScimValue.Complex
                    ?: return Result.failure(
                        ScimFailure(
                            ScimErrorType.invalidValue,
                            "members[] entries must be complex objects with a 'value' sub-attribute, got $rawEntry",
                        ),
                    )
            // Case-insensitive: this guards a security decision (reject nesting rather than
            // flatten it), and RFC 7643's examples use "Group" but do not guarantee a connector
            // sends that exact casing. A lowercase "group" must not slip past it.
            val type = (entry.attributes["type"] as? ScimValue.Str)?.value
            if (type?.equals(MEMBER_TYPE_GROUP, ignoreCase = true) == true) {
                return Result.failure(
                    ScimFailure(
                        ScimErrorType.invalidValue,
                        "nested group members are not supported; Kotauth models group nesting " +
                            "via parentGroupId, not direct membership",
                    ),
                )
            }

            val rawValue = (entry.attributes["value"] as? ScimValue.Str)?.value?.trim()
            val id =
                rawValue?.toIntOrNull()
                    ?: return Result.failure(
                        ScimFailure(ScimErrorType.invalidValue, "members[].value must be a numeric user id"),
                    )
            memberIds += UserId(id)
        }
        return Result.success(memberIds)
    }
}
