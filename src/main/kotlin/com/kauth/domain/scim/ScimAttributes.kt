package com.kauth.domain.scim

/**
 * The JSON shape RFC 7643 defines for a SCIM attribute value.
 *
 * A cast such as `as? ScimValue.Str` yields null for two very different inputs — "absent" and
 * "present, but the wrong JSON type" — and every mapper used to collapse the two. A wrongly-shaped
 * value was discarded, or, for an attribute a PUT reads as "clear when absent", used to erase the
 * stored one, all under a 200 OK that told the caller the write had succeeded. Declaring the
 * expected shape once and checking it at each mapper's entry point turns that into a single
 * `invalidValue` failure covering every attribute at once, rather than a guard per attribute added
 * one reported defect at a time.
 */
internal enum class ScimShape(
    /** Reads into "'active' must be a boolean, got a string". */
    val description: String,
) {
    STRING("a string"),
    BOOLEAN("a boolean"),
    COMPLEX("an object"),
    STRING_ARRAY("an array of strings"),
    COMPLEX_ARRAY("an array of objects"),
    ;

    private val elementShape: ScimShape?
        get() =
            when (this) {
                STRING_ARRAY -> STRING
                COMPLEX_ARRAY -> COMPLEX
                else -> null
            }

    private fun accepts(value: ScimValue): Boolean =
        when (this) {
            STRING -> value is ScimValue.Str
            BOOLEAN -> value is ScimValue.Bool
            COMPLEX -> value is ScimValue.Complex
            STRING_ARRAY, COMPLEX_ARRAY -> value is ScimValue.MultiValued
        }

    /** The failure [value] earns under this shape, or null when it conforms. */
    fun mismatch(
        name: String,
        value: ScimValue,
    ): ScimFailure? {
        if (!accepts(value)) return shapeFailure(name, description, value)
        val element = elementShape ?: return null
        if (value !is ScimValue.MultiValued) return null
        // The offending index is named because a caller cannot otherwise tell which of fifty
        // member entries was rejected; the entry itself is never echoed (see shapeFailure).
        value.values.forEachIndexed { index, entry ->
            if (!element.accepts(entry)) return shapeFailure("$name[$index]", element.description, entry)
        }
        return null
    }
}

// Details name the attribute and the shape that arrived, never the value: an entry can be a
// megabyte-long string or nested to the parser's depth cap, and echoing it puts unbounded caller
// input into the 400 body and into anything that logs responses.
private fun shapeFailure(
    name: String,
    expected: String,
    actual: ScimValue,
): ScimFailure = ScimFailure(ScimErrorType.invalidValue, "'$name' must be $expected, got ${actual.shapeName()}")

/**
 * Every attribute this implementation defines, with the shape its value must have. One table for
 * both resource types on purpose: no attribute name means a different shape for a User than for a
 * Group, and sharing it is what stops `/Users` and `/Groups` disagreeing about identical malformed
 * input — one answering 400 where the other answers 200.
 *
 * This is also the vocabulary of known attributes [ScimPatchEngine] validates a PATCH path
 * against.
 */
internal val SCIM_ATTRIBUTE_SHAPES: Map<String, ScimShape> =
    mapOf(
        "userName" to ScimShape.STRING,
        "externalId" to ScimShape.STRING,
        "displayName" to ScimShape.STRING,
        "password" to ScimShape.STRING,
        "id" to ScimShape.STRING,
        "active" to ScimShape.BOOLEAN,
        "name" to ScimShape.COMPLEX,
        "meta" to ScimShape.COMPLEX,
        "schemas" to ScimShape.STRING_ARRAY,
        "emails" to ScimShape.COMPLEX_ARRAY,
        "members" to ScimShape.COMPLEX_ARRAY,
        "groups" to ScimShape.COMPLEX_ARRAY,
    )

/**
 * Rejects any PRESENT attribute whose value has the wrong JSON shape, and any attribute name this
 * implementation does not define. Called once at each mapper's `toDomain` entry point, so PUT,
 * POST and the PATCH-merged resource all inherit it and the per-attribute `as?` casts downstream
 * are unreachable rather than silently lossy.
 *
 * Absent (`attributes[name] == null`) and explicitly cleared ([ScimValue.Null]) are deliberately
 * left alone: each mapper's two-tier absent-value policy — a nullable column clears, a NOT NULL
 * column falls back to the stored value — is a separate, documented decision from this one.
 *
 * No coercion, deliberately. `{"active":"false"}` as a string is real connector behaviour and the
 * tempting fix is to read it as a boolean, but a coerced deprovision that quietly does nothing is
 * a security defect where a 400 is an operator's cue to fix the mapping. A vendor dialect layer,
 * not these core mappers, is where that leniency belongs.
 */
internal fun ScimResource.validateAttributeShapes(): Result<Unit> {
    for ((name, value) in attributes) {
        val shape = SCIM_ATTRIBUTE_SHAPES[name] ?: continue
        if (value == ScimValue.Null) continue
        shape.mismatch(name, value)?.let { return Result.failure(it) }
    }
    return Result.success(Unit)
}

// users.external_id and groups.external_id are both VARCHAR(255). Without this check an overlong
// value reaches Postgres and comes back a 500, which connectors treat as retryable and loop on,
// where invalidValue is terminal and names what the operator has to shorten. displayName already
// gets the same treatment at its own column width.
internal const val EXTERNAL_ID_MAX_LENGTH = 255

/**
 * The one reading of `externalId` both resource types share: trimmed, because surrounding
 * whitespace is never meaningful and creates duplicates the tenant-unique index cannot see, but
 * never lower-cased, because case may be significant to the identity provider. Blank reads as
 * absent so it clears rather than storing "".
 */
internal fun ScimResource.parseExternalId(): Result<String?> {
    val value =
        (attributes["externalId"] as? ScimValue.Str)
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return Result.success(null)
    if (value.length > EXTERNAL_ID_MAX_LENGTH) {
        return Result.failure(
            ScimFailure(ScimErrorType.invalidValue, "externalId exceeds $EXTERNAL_ID_MAX_LENGTH characters"),
        )
    }
    return Result.success(value)
}
