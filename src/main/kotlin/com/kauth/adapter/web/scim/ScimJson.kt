package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimPatchOp
import com.kauth.domain.scim.ScimPatchOpType
import com.kauth.domain.scim.ScimPath
import com.kauth.domain.scim.ScimResource
import com.kauth.domain.scim.ScimValue
import com.kauth.domain.scim.canonicalScimAttributeName
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

// Bounds recursion depth against a pathologically nested request body; not a grammar rule.
// Matches the filter parser's cap so the two decoders agree on what "too deep" means.
private const val MAX_JSON_NESTING_DEPTH = 32

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
    // `schemas` is lifted out of the attribute map, so the shape table never sees it. Checking it
    // here is what keeps `{"schemas":"..."}` from being silently ignored the way every other
    // wrongly-shaped value used to be.
    val rawSchemas = obj.entries.firstOrNull { canonicalScimAttributeName(it.key) == SCHEMAS_KEY }?.value
    val schemas =
        when {
            rawSchemas == null || rawSchemas is JsonNull -> emptyList()
            rawSchemas !is JsonArray ->
                return Result.failure(
                    ScimFailure(ScimErrorType.invalidValue, "'schemas' must be an array of strings"),
                )
            rawSchemas.any { (it as? JsonPrimitive)?.isString != true } ->
                return Result.failure(
                    ScimFailure(ScimErrorType.invalidValue, "'schemas' must be an array of strings"),
                )
            else -> rawSchemas.map { (it as JsonPrimitive).content }
        }
    val attributes = mutableMapOf<String, ScimValue>()
    for ((key, value) in obj.entries) {
        // RFC 7643 §2.1: attribute names are case-insensitive. Respelling them canonically here
        // is what lets every downstream exact-match lookup stay an exact-match lookup.
        val name = canonicalScimAttributeName(key)
        if (name == SCHEMAS_KEY) continue
        attributes[name] = value.toScimValue().getOrElse { return Result.failure(it) }
    }
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
        val value =
            if ("value" in opObj) {
                opObj.getValue("value").toScimValue().getOrElse { return Result.failure(it) }
            } else {
                null
            }
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

// depth counts JsonObject/JsonArray levels already descended through, so a pathologically
// nested body fails as a value (400) instead of unwinding the JVM stack (StackOverflowError,
// an Error the Result contract cannot carry).
private fun JsonElement.toScimValue(depth: Int = 0): Result<ScimValue> {
    if (depth > MAX_JSON_NESTING_DEPTH) {
        return Result.failure(
            ScimFailure(ScimErrorType.invalidSyntax, "value nesting exceeds maximum depth of $MAX_JSON_NESTING_DEPTH"),
        )
    }
    return when (this) {
        is JsonNull -> Result.success(ScimValue.Null)
        is JsonPrimitive ->
            when {
                isString -> Result.success(ScimValue.Str(content))
                else ->
                    booleanOrNull?.let { Result.success(ScimValue.Bool(it)) }
                        ?: longOrNull?.let { Result.success(ScimValue.Num(it)) }
                        // The domain model only carries integer numbers. Coercing a fractional
                        // or overflowing literal into Str would silently change its type for
                        // any caller matching on ScimValue.Num — fail instead of surprise them.
                        ?: Result.failure(
                            ScimFailure(
                                ScimErrorType.invalidSyntax,
                                "'$content' is not a boolean or an integer number",
                            ),
                        )
            }
        is JsonObject -> {
            val attributes = mutableMapOf<String, ScimValue>()
            for ((key, value) in entries) {
                // Sub-attribute names are case-insensitive too, so `{"name":{"givenname":"Ada"}}`
                // has to reach the mapper's `attributes["givenName"]` read.
                attributes[canonicalScimAttributeName(key)] =
                    value.toScimValue(depth + 1).getOrElse { return Result.failure(it) }
            }
            Result.success(ScimValue.Complex(attributes))
        }
        is JsonArray -> {
            val values = mutableListOf<ScimValue>()
            for (element in this) {
                values.add(element.toScimValue(depth + 1).getOrElse { return Result.failure(it) })
            }
            Result.success(ScimValue.MultiValued(values))
        }
    }
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
