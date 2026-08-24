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
 * One attribute's declared shape, plus the shapes of the sub-attributes this implementation
 * actually reads out of it.
 *
 * [subAttributes] is deliberately a partial vocabulary rather than a closed one: RFC 7643 gives
 * `name` and `emails` more parts than Kotauth stores, and a name absent from it is left unchecked
 * and unread. Rejecting those would drop whole records over data nothing looks at, which is the
 * failure mode a strict unknown-*attribute* rule already has to be careful about one level up.
 */
internal data class ScimAttributeSpec(
    val shape: ScimShape,
    val subAttributes: Map<String, ScimShape> = emptyMap(),
)

/**
 * Every attribute this implementation defines, with the shape its value must have. One table for
 * both resource types on purpose: no attribute name means a different shape for a User than for a
 * Group, and sharing it is what stops `/Users` and `/Groups` disagreeing about identical malformed
 * input — one answering 400 where the other answers 200.
 *
 * The table reaches one level down, because the same defect hides there: `{"name":{"givenName":123}}`
 * and `{"emails":[{"value":123}]}` both satisfy the top-level shape, and the sub-attribute cast that
 * follows used to yield null and either discard the value or erase the stored one, under a 200 OK.
 *
 * `groups` and `meta` declare no sub-attribute shapes: both are server-managed, no `toDomain` reads
 * anything inside them, and a resource echoed back by a read-modify-write client carries them
 * verbatim — there is nothing there to erase and nothing to gain by refusing it.
 *
 * This is also the vocabulary of known attributes: a name absent from it is a typo (or an
 * unimplemented extension), which [validateAttributeShapes] and [ScimPatchEngine] both reject
 * rather than silently drop.
 */
internal val SCIM_ATTRIBUTE_SHAPES: Map<String, ScimAttributeSpec> =
    mapOf(
        "userName" to ScimAttributeSpec(ScimShape.STRING),
        "externalId" to ScimAttributeSpec(ScimShape.STRING),
        "displayName" to ScimAttributeSpec(ScimShape.STRING),
        "password" to ScimAttributeSpec(ScimShape.STRING),
        "id" to ScimAttributeSpec(ScimShape.STRING),
        "active" to ScimAttributeSpec(ScimShape.BOOLEAN),
        "name" to
            ScimAttributeSpec(
                ScimShape.COMPLEX,
                mapOf("givenName" to ScimShape.STRING, "familyName" to ScimShape.STRING),
            ),
        "meta" to ScimAttributeSpec(ScimShape.COMPLEX),
        "schemas" to ScimAttributeSpec(ScimShape.STRING_ARRAY),
        "emails" to
            ScimAttributeSpec(
                ScimShape.COMPLEX_ARRAY,
                mapOf(
                    "value" to ScimShape.STRING,
                    "type" to ScimShape.STRING,
                    "primary" to ScimShape.BOOLEAN,
                ),
            ),
        "members" to
            ScimAttributeSpec(
                ScimShape.COMPLEX_ARRAY,
                mapOf(
                    "value" to ScimShape.STRING,
                    "type" to ScimShape.STRING,
                    "display" to ScimShape.STRING,
                    "\$ref" to ScimShape.STRING,
                ),
            ),
        "groups" to ScimAttributeSpec(ScimShape.COMPLEX_ARRAY),
    )

/** The failure [value] earns under this spec, sub-attributes included, or null when it conforms. */
internal fun ScimAttributeSpec.mismatch(
    name: String,
    value: ScimValue,
): ScimFailure? {
    shape.mismatch(name, value)?.let { return it }
    if (subAttributes.isEmpty()) return null
    return when (value) {
        is ScimValue.Complex -> subMismatch(name, value)
        // The element type is already guaranteed by ScimShape.mismatch above, so a non-Complex
        // entry cannot reach here for a COMPLEX_ARRAY.
        is ScimValue.MultiValued ->
            value.values
                .asSequence()
                .mapIndexedNotNull { index, entry ->
                    (entry as? ScimValue.Complex)?.let { subMismatch("$name[$index]", it) }
                }.firstOrNull()
        else -> null
    }
}

private fun ScimAttributeSpec.subMismatch(
    path: String,
    value: ScimValue.Complex,
): ScimFailure? {
    for ((sub, subValue) in value.attributes) {
        if (subValue == ScimValue.Null) continue
        val subShape = subAttributes[sub] ?: continue
        subShape.mismatch("$path.$sub", subValue)?.let { return it }
    }
    return null
}

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
        // An undefined name is a typo, and dropping it silently is how the singular `"member"`
        // returned 200 while emptying the group — the same typo a PATCH path already rejects.
        // invalidSyntax rather than PATCH's invalidPath: there is no path in a PUT or POST body.
        val spec =
            SCIM_ATTRIBUTE_SHAPES[name]
                ?: return Result.failure(ScimFailure(ScimErrorType.invalidSyntax, "unknown attribute '$name'"))
        if (value == ScimValue.Null) continue
        spec.mismatch(name, value)?.let { return Result.failure(it) }
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
