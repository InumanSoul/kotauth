package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimPatchOp
import com.kauth.domain.scim.ScimResource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

private const val OPERATIONS_KEY = "Operations"
private const val OP_KEY = "op"
private const val PATH_KEY = "path"
private const val VALUE_KEY = "value"
private const val ACTIVE_ATTRIBUTE = "active"

private val KNOWN_VERBS = setOf("add", "replace", "remove")

/**
 * Reshapes the documented wire deviations of one enterprise provisioning client into the
 * canonical form, then hands the result to [RfcDialect] for the single canonical parse.
 *
 * It normalises shape only. What an operation *means* stays with the patch engine, which is
 * deliberately vendor-blind, and a payload this dialect cannot map to a canonical shape fails
 * rather than being guessed at — a deprovision that quietly does nothing is worse than a 400.
 */
object EntraDialect : ScimDialect {
    override val id = "entra"

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
        return RfcDialect.normalizeResource(JsonObject(obj.mapValues { (name, value) -> coerceIfActive(name, value) }))
    }
}

private fun normalizeOperation(operation: JsonObject): Result<JsonObject> {
    val rawVerb = (operation[OP_KEY] as? JsonPrimitive)?.contentOrNull
    val verb = rawVerb?.lowercase()
    // An absent or unrecognised verb is named by the canonical parser, not re-worded here.
    if (verb == null || verb !in KNOWN_VERBS) return Result.success(operation)
    val path = operation[PATH_KEY]?.takeUnless { it is JsonNull }
    val hasValue = VALUE_KEY in operation
    if (verb != "remove") {
        if (!hasValue) {
            return Result.failure(
                ScimFailure(
                    ScimErrorType.invalidSyntax,
                    "a '$verb' operation must carry a 'value'; this dialect will not infer one",
                ),
            )
        }
        if (path == null && operation[VALUE_KEY] !is JsonObject) {
            return Result.failure(
                ScimFailure(
                    ScimErrorType.invalidSyntax,
                    "a '$verb' operation with no 'path' must carry an object 'value' to merge",
                ),
            )
        }
    }
    return Result.success(
        buildJsonObject {
            operation.forEach { (key, value) -> put(key, value) }
            // The verb arrives capitalised; lower-casing it here keeps the dialect independent of
            // whatever leniency the canonical parser happens to allow.
            put(OP_KEY, JsonPrimitive(verb))
            if (hasValue) put(VALUE_KEY, normalizeValue(path, operation.getValue(VALUE_KEY)))
        },
    )
}

/**
 * A value with no path is a partial resource to merge (RFC 7644 §3.5.2), so `active` is looked
 * for among its attributes; with a path, the path itself says whether the scalar is `active`.
 */
private fun normalizeValue(
    path: JsonElement?,
    value: JsonElement,
): JsonElement =
    when {
        path == null ->
            (value as? JsonObject)
                ?.let { JsonObject(it.mapValues { (name, attribute) -> coerceIfActive(name, attribute) }) }
                ?: value
        (path as? JsonPrimitive)?.contentOrNull?.trim()?.equals(ACTIVE_ATTRIBUTE, ignoreCase = true) == true ->
            coerceBoolean(value)
        else -> value
    }

private fun coerceIfActive(
    name: String,
    value: JsonElement,
): JsonElement = if (name.equals(ACTIVE_ATTRIBUTE, ignoreCase = true)) coerceBoolean(value) else value

/**
 * Reads `"False"` as the boolean the RFC requires. This leniency lives here and nowhere else:
 * the core mappers reject a string `active` on purpose, because a coerced deprovision that
 * quietly does nothing is a security defect, while a 400 is an operator's cue to fix the mapping.
 * A string that is not a boolean literal is left alone for the core to reject.
 */
private fun coerceBoolean(value: JsonElement): JsonElement {
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
    return when (text.lowercase()) {
        "true" -> JsonPrimitive(true)
        "false" -> JsonPrimitive(false)
        else -> value
    }
}
