package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimPatchOp
import com.kauth.domain.scim.ScimPatchOpType
import com.kauth.domain.scim.ScimPath
import com.kauth.domain.scim.ScimResource
import com.kauth.domain.scim.ScimValue
import com.kauth.domain.scim.parsePath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val SCHEMAS_KEY = "schemas"
private const val OPERATIONS_KEY = "Operations"

/**
 * Decodes a JSON resource body into the protocol's neutral tree.
 *
 * A key present with a JSON `null` becomes [ScimValue.Null]; a key that never appears in the
 * object simply never appears in [ScimResource.attributes]. Iterating [JsonObject.entries]
 * preserves that distinction for free — an absent key has no entry to iterate.
 */
fun JsonElement.toScimResource(): Result<ScimResource> {
    val obj =
        this as? JsonObject
            ?: return Result.failure(ScimFailure(ScimErrorType.invalidSyntax, "a SCIM resource must be a JSON object"))
    val schemas =
        (obj[SCHEMAS_KEY] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
    val attributes =
        obj.entries
            .filter { it.key != SCHEMAS_KEY }
            .associate { (key, value) -> key to value.toScimValue() }
    return Result.success(ScimResource(schemas = schemas, attributes = attributes))
}

/** Encodes a resource to the SCIM wire format. */
fun ScimResource.toJson(): JsonObject =
    buildJsonObject {
        putJsonArray(SCHEMAS_KEY) { schemas.forEach { add(it) } }
        attributes.forEach { (key, value) -> put(key, value.toJsonElement()) }
    }

/**
 * Decodes a PATCH request body (RFC 7644 §3.5.2) into ordered operations. An operation with
 * no `path` carries a partial resource in `value`, to be merged rather than targeted.
 */
fun JsonElement.toScimPatchOps(): Result<List<ScimPatchOp>> {
    val obj =
        this as? JsonObject
            ?: return Result.failure(ScimFailure(ScimErrorType.invalidSyntax, "a PATCH body must be a JSON object"))
    val operations =
        obj[OPERATIONS_KEY] as? JsonArray
            ?: return Result.failure(
                ScimFailure(ScimErrorType.invalidSyntax, "a PATCH body must contain an '$OPERATIONS_KEY' array"),
            )
    val ops = mutableListOf<ScimPatchOp>()
    for (element in operations) {
        val opObj =
            element as? JsonObject
                ?: return Result.failure(
                    ScimFailure(ScimErrorType.invalidSyntax, "each operation must be a JSON object"),
                )
        val opType = opObj.toScimPatchOpType().getOrElse { return Result.failure(it) }
        val path = opObj.toScimPath().getOrElse { return Result.failure(it) }
        // "value" absent means no value at all (Kotlin null); "value": null means an explicit
        // ScimValue.Null — the same distinction toScimResource preserves for resource bodies.
        val value = if ("value" in opObj) opObj.getValue("value").toScimValue() else null
        ops.add(ScimPatchOp(opType, path, value))
    }
    return Result.success(ops)
}

private fun JsonObject.toScimPatchOpType(): Result<ScimPatchOpType> {
    val raw = (this["op"] as? JsonPrimitive)?.contentOrNull
    return when (raw?.lowercase()) {
        "add" -> Result.success(ScimPatchOpType.ADD)
        "replace" -> Result.success(ScimPatchOpType.REPLACE)
        "remove" -> Result.success(ScimPatchOpType.REMOVE)
        else -> Result.failure(ScimFailure(ScimErrorType.invalidSyntax, "unknown or missing PATCH op '$raw'"))
    }
}

private fun JsonObject.toScimPath(): Result<ScimPath?> =
    when (val pathElement = this["path"]) {
        null, is JsonNull -> Result.success(null)
        is JsonPrimitive ->
            if (pathElement.isString) {
                parsePath(pathElement.content)
            } else {
                Result.failure(ScimFailure(ScimErrorType.invalidSyntax, "'path' must be a string"))
            }
        else -> Result.failure(ScimFailure(ScimErrorType.invalidSyntax, "'path' must be a string"))
    }

private fun JsonElement.toScimValue(): ScimValue =
    when (this) {
        is JsonNull -> ScimValue.Null
        is JsonPrimitive ->
            when {
                isString -> ScimValue.Str(content)
                else ->
                    booleanOrNull?.let { ScimValue.Bool(it) }
                        ?: longOrNull?.let { ScimValue.Num(it) }
                        // The domain model only carries integer numbers; anything else is
                        // preserved as text rather than silently dropped.
                        ?: ScimValue.Str(content)
            }
        is JsonObject -> ScimValue.Complex(entries.associate { (key, value) -> key to value.toScimValue() })
        is JsonArray -> ScimValue.MultiValued(map { it.toScimValue() })
    }

private fun ScimValue.toJsonElement(): JsonElement =
    when (this) {
        is ScimValue.Str -> JsonPrimitive(value)
        is ScimValue.Bool -> JsonPrimitive(value)
        is ScimValue.Num -> JsonPrimitive(value)
        is ScimValue.Complex ->
            buildJsonObject {
                attributes.forEach { (key, value) ->
                    put(key, value.toJsonElement())
                }
            }
        is ScimValue.MultiValued -> buildJsonArray { values.forEach { add(it.toJsonElement()) } }
        ScimValue.Null -> JsonNull
    }
