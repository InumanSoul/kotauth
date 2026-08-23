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
    fun toResource(
        user: User,
        groups: List<Group> = emptyList(),
    ): ScimResource {
        val attributes = mutableMapOf<String, ScimValue>()

        user.id?.let { attributes["id"] = ScimValue.Str(it.value.toString()) }
        attributes["userName"] = ScimValue.Str(user.username)
        user.externalId?.let { attributes["externalId"] = ScimValue.Str(it) }
        attributes["active"] = ScimValue.Bool(user.enabled)

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

        val explicitDisplayName = (resource.attributes["displayName"] as? ScimValue.Str)?.value
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
        // because case may be significant to the IdP.
        val externalId =
            (resource.attributes["externalId"] as? ScimValue.Str)
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: existing?.externalId

        val email =
            (resource.attributes["emails"] as? ScimValue.MultiValued)
                ?.values
                ?.filterIsInstance<ScimValue.Complex>()
                ?.firstOrNull { (it.attributes["type"] as? ScimValue.Str)?.value == "work" }
                ?.let { (it.attributes["value"] as? ScimValue.Str)?.value }
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
}
