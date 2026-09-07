package com.kauth.adapter.web.scim

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimPatchOp
import com.kauth.domain.scim.ScimResource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val OPERATIONS_KEY = "Operations"
private const val PATH_KEY = "path"
private const val VALUE_KEY = "value"
private const val MEMBERS_ATTRIBUTE = "members"
private const val DISPLAY_SUB_ATTRIBUTE = "display"

/**
 * Reshapes the documented wire deviations of one enterprise provisioning client into the
 * canonical form, then hands the result to [RfcDialect] for the single canonical parse.
 *
 * Its whole job is dropping the advisory `display` a group push carries alongside each member
 * id. Nothing here is about persistence: no mapper reads `display` in either direction, so it is
 * never stored under any dialect, and RFC 7643 §4.2 marks it read-only anyway. What stripping it
 * changes is what gets *rejected* — the shape table declares `members[].display` a string, so a
 * push sending a non-string there loses every member in the request under `rfc`, where this
 * dialect drops the sub-attribute before the shape check sees it. The id is the only part that
 * identifies anyone, and it is the only part that survives either way.
 *
 * Everything else passes through. A deactivation arrives as a `replace` on a proper `active`
 * path, which is already canonical, and a payload this dialect cannot map to member entries
 * fails rather than being guessed at — inventing a member id is not normalising, it is deciding
 * what the caller meant.
 */
object OktaDialect : ScimDialect {
    override val id = "okta"

    override val label = EnglishStrings.SCIM_DIALECT_OKTA_LABEL

    override val setupNotes = EnglishStrings.SCIM_DIALECT_OKTA_NOTES

    override fun normalizeOps(body: JsonElement): Result<List<ScimPatchOp>> {
        val obj = body as? JsonObject ?: return RfcDialect.normalizeOps(body)
        // A body that is not a PatchOp envelope is nothing this dialect can reshape; the canonical
        // parser already words that failure, so it stays the one place that does.
        val operations = obj[OPERATIONS_KEY] as? JsonArray ?: return RfcDialect.normalizeOps(body)
        val normalized = mutableListOf<JsonElement>()
        for (element in operations) {
            val operation = element as? JsonObject ?: return RfcDialect.normalizeOps(body)
            normalized += normalizeOperation(operation).getOrElse { return Result.failure(it) }
        }
        return RfcDialect.normalizeOps(JsonObject(obj + (OPERATIONS_KEY to JsonArray(normalized))))
    }

    override fun normalizeResource(body: JsonElement): Result<ScimResource> {
        val obj = body as? JsonObject ?: return RfcDialect.normalizeResource(body)
        return RfcDialect.normalizeResource(
            stripMembersDisplay(obj).getOrElse { return Result.failure(it) },
        )
    }
}

private fun normalizeOperation(operation: JsonObject): Result<JsonObject> {
    val value = operation[VALUE_KEY]?.takeUnless { it is JsonNull } ?: return Result.success(operation)
    val path = operation[PATH_KEY]?.takeUnless { it is JsonNull }
    val normalized =
        when {
            // A value with no path is a partial resource to merge (RFC 7644 §3.5.2), so `members`
            // is looked for among its attributes; with a path, the path says what the value is.
            path == null -> (value as? JsonObject)?.let { stripMembersDisplay(it) } ?: return Result.success(operation)
            path.targetsMembers() -> stripMemberEntries(value)
            else -> return Result.success(operation)
        }.getOrElse { return Result.failure(it) }
    return Result.success(JsonObject(operation + (VALUE_KEY to normalized)))
}

/** True only for the bare `members` attribute — `members[value eq "42"]` targets one entry, not the array. */
private fun JsonElement.targetsMembers(): Boolean =
    (this as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.equals(MEMBERS_ATTRIBUTE, ignoreCase = true) == true

/** Rewrites a resource or partial resource's `members`, leaving every other attribute untouched. */
private fun stripMembersDisplay(obj: JsonObject): Result<JsonObject> {
    // RFC 7643 §2.1: attribute names are case-insensitive, so the key is matched, not assumed.
    val members =
        obj.entries.firstOrNull { it.key.equals(MEMBERS_ATTRIBUTE, ignoreCase = true) }
            ?: return Result.success(obj)
    if (members.value is JsonNull) return Result.success(obj)
    val stripped = stripMemberEntries(members.value).getOrElse { return Result.failure(it) }
    return Result.success(JsonObject(obj + (members.key to stripped)))
}

private fun stripMemberEntries(value: JsonElement): Result<JsonElement> =
    when (value) {
        is JsonArray -> {
            val entries = mutableListOf<JsonElement>()
            for (entry in value) {
                entries += (entry as? JsonObject)?.let(::withoutDisplay) ?: return unmappableMembers()
            }
            Result.success(JsonArray(entries))
        }
        is JsonObject -> Result.success(withoutDisplay(value))
        else -> unmappableMembers()
    }

private fun withoutDisplay(entry: JsonObject): JsonObject =
    JsonObject(entry.filterKeys { !it.equals(DISPLAY_SUB_ATTRIBUTE, ignoreCase = true) })

private fun unmappableMembers(): Result<Nothing> =
    Result.failure(
        ScimFailure(
            ScimErrorType.invalidValue,
            "a '$MEMBERS_ATTRIBUTE' value must be member objects carrying a '$VALUE_KEY' sub-attribute; " +
                "this dialect will not infer one from a bare id",
        ),
    )
