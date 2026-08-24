package com.kauth.domain.scim

import com.kauth.domain.model.Group
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User

private const val USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User"

// full_name is VARCHAR(255) NOT NULL. Two long name parts can exceed it even though
// each fits alone, so the composed (or explicit) value is checked before it is stored.
private const val FULL_NAME_MAX_LENGTH = 255

/**
 * The result of mapping an inbound SCIM resource onto the domain. [plaintextPassword] carries
 * a supplied password separately from [user] — the mapper never hashes it, so the caller's
 * usual password-policy checks still apply before it is persisted.
 */
data class ScimUserWrite(
    val user: User,
    val plaintextPassword: String?,
)

/** Translates between the SCIM User schema (RFC 7643 §4.1) and the domain [User]. Pure mapping: no I/O. */
object ScimUserMapper {
    /**
     * [location] is the absolute URL of this resource (RFC 7644 §3.1) — the caller resolves it
     * from the request, since the mapper does no I/O and doesn't know the base URL. Passing
     * `null` (the merge-baseline use inside `ScimPatchEngine`'s PATCH handling, never a
     * client-facing response) omits `meta.location` but still emits `meta.resourceType`.
     *
     * `meta.lastModified` is deliberately never emitted: [User] has no update-tracking timestamp,
     * only [User.createdAt] — fabricating a "last modified" value from creation time would tell a
     * connector doing delta sync that nothing has changed since creation, which is worse than the
     * attribute being absent (RFC 7643 marks every `meta` sub-attribute OPTIONAL).
     */
    fun toResource(
        user: User,
        groups: List<Group> = emptyList(),
        location: String? = null,
    ): ScimResource {
        val attributes = mutableMapOf<String, ScimValue>()

        user.id?.let { attributes["id"] = ScimValue.Str(it.value.toString()) }
        attributes["userName"] = ScimValue.Str(user.username)
        user.externalId?.let { attributes["externalId"] = ScimValue.Str(it) }
        attributes["active"] = ScimValue.Bool(user.enabled)
        attributes["meta"] =
            ScimValue.Complex(
                buildMap {
                    put("resourceType", ScimValue.Str("User"))
                    location?.let { put("location", ScimValue.Str(it)) }
                    user.createdAt?.let { put("created", ScimValue.Str(it.toString())) }
                },
            )

        // Never reverse-engineer name parts by splitting fullName: a fabricated value is
        // worse than an honest gap, and splitting on whitespace is wrong for many names.
        if (user.givenName != null || user.familyName != null) {
            attributes["name"] =
                ScimValue.Complex(
                    buildMap {
                        user.givenName?.let { put("givenName", ScimValue.Str(it)) }
                        user.familyName?.let { put("familyName", ScimValue.Str(it)) }
                    },
                )
        }

        attributes["displayName"] = ScimValue.Str(user.fullName)

        attributes["emails"] =
            ScimValue.MultiValued(
                listOf(
                    ScimValue.Complex(
                        mapOf(
                            "value" to ScimValue.Str(user.email),
                            "type" to ScimValue.Str("work"),
                        ),
                    ),
                ),
            )

        if (groups.isNotEmpty()) {
            attributes["groups"] =
                ScimValue.MultiValued(
                    groups.map { group ->
                        ScimValue.Complex(
                            buildMap {
                                group.id?.let { put("value", ScimValue.Str(it.value.toString())) }
                                put("display", ScimValue.Str(group.name))
                            },
                        )
                    },
                )
        }

        // password is write-only and is never emitted here, in any form.
        return ScimResource(schemas = listOf(USER_SCHEMA), attributes = attributes)
    }

    /**
     * [resource] must be a fully merged representation — the complete desired state, not a bare
     * PATCH body. Callers handling PATCH must merge the operations onto [ScimUserMapper.toResource]
     * of the current user first (see `ScimPatchEngine`) and pass the merged result here — never the
     * raw PATCH body.
     *
     * **Absent-attribute policy (PUT is a full replace, RFC 7644 §3.5.1):** an attribute missing
     * from [resource] clears the corresponding field — [externalId], [User.givenName], and
     * [User.familyName] all go to `null` when omitted. The two exceptions are `email` and
     * `fullName`/`displayName`: both back a `NOT NULL` database column, so "clear" isn't a value
     * they can take. Omitting them instead falls back to [existing]'s value, and on create (no
     * [existing]) an omitted email is rejected downstream by [com.kauth.domain.service.AdminUserService]
     * ("A valid email address is required."). Silently keeping a stale `externalId` across a PUT
     * that dropped it would leave a dangling correlation key the IdP believes it removed.
     */
    fun toDomain(
        resource: ScimResource,
        existing: User?,
        tenantId: TenantId,
    ): Result<ScimUserWrite> {
        val username = (resource.attributes["userName"] as? ScimValue.Str)?.value?.trim()
        if (username.isNullOrEmpty()) {
            return Result.failure(ScimFailure(ScimErrorType.invalidValue, "userName is required"))
        }

        val name = resource.attributes["name"] as? ScimValue.Complex
        val givenName = (name?.attributes?.get("givenName") as? ScimValue.Str)?.value
        val familyName = (name?.attributes?.get("familyName") as? ScimValue.Str)?.value

        // A blank displayName ("" or whitespace) is common in connector payloads for a
        // cleared field; treated as absent so the chain falls through instead of storing "".
        val explicitDisplayName =
            (resource.attributes["displayName"] as? ScimValue.Str)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val composedName =
            listOfNotNull(givenName, familyName)
                .joinToString(" ")
                .trim()
                .takeIf { it.isNotEmpty() }

        // An explicit displayName outranks one we derive from the name parts.
        val fullName = explicitDisplayName ?: composedName ?: existing?.fullName ?: username
        if (fullName.length > FULL_NAME_MAX_LENGTH) {
            return Result.failure(
                ScimFailure(ScimErrorType.invalidValue, "displayName exceeds $FULL_NAME_MAX_LENGTH characters"),
            )
        }

        // externalId is an opaque IdP key: trimmed because surrounding whitespace is never
        // meaningful and creates duplicates the unique index can't see, but never lower-cased
        // because case may be significant to the IdP. It is nullable and not NOT NULL-constrained,
        // so — unlike email/fullName below — a PUT that omits it clears it (see KDoc above):
        // an IdP unlinking a user by dropping externalId must not leave the stale key in place.
        val externalId =
            (resource.attributes["externalId"] as? ScimValue.Str)
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        // Same emptiness guard as displayName: a work entry with value "" must fall through
        // to the existing address rather than blank it.
        val email =
            resource
                .selectEmail()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: existing?.email
                ?: ""

        val active = (resource.attributes["active"] as? ScimValue.Bool)?.value ?: existing?.enabled ?: true

        // password is write-only: a supplied value is surfaced only via plaintextPassword.
        // The mapper never hashes it and never writes it into passwordHash — an absent
        // password on update must preserve the existing hash, not silently clear it.
        val plaintextPassword = (resource.attributes["password"] as? ScimValue.Str)?.value
        val passwordHash = existing?.passwordHash ?: User.SENTINEL_PASSWORD_HASH

        val base =
            existing ?: User(
                tenantId = tenantId,
                username = username,
                email = email,
                fullName = fullName,
                passwordHash = passwordHash,
            )

        val user =
            base.copy(
                tenantId = existing?.tenantId ?: tenantId,
                username = username,
                email = email,
                fullName = fullName,
                passwordHash = passwordHash,
                externalId = externalId,
                givenName = givenName,
                familyName = familyName,
                enabled = active,
            )

        return Result.success(ScimUserWrite(user, plaintextPassword))
    }

    /**
     * Picks the address to use from a SCIM `emails` array (RFC 7643 §4.1.2): the entry marked
     * `type: "work"`, then the one marked `primary: true`, then the first entry — in that order.
     * Many connectors send a single email with neither `type` nor `primary` set; requiring an
     * exact `type == "work"` match silently drops that address on create (blank email, wrong
     * validation error) and on update (address ignored, stale one kept).
     */
    private fun ScimResource.selectEmail(): String? {
        val entries = (attributes["emails"] as? ScimValue.MultiValued)?.values?.filterIsInstance<ScimValue.Complex>()
        val chosen =
            entries
                ?.firstOrNull { (it.attributes["type"] as? ScimValue.Str)?.value == "work" }
                ?: entries?.firstOrNull { (it.attributes["primary"] as? ScimValue.Bool)?.value == true }
                ?: entries?.firstOrNull()
        return (chosen?.attributes?.get("value") as? ScimValue.Str)?.value
    }
}
