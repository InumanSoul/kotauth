package com.kauth.domain.scim

/**
 * A SCIM attribute value. PATCH operates on paths rather than fields, so
 * resources are held as a neutral tree — a typed model would force a special
 * case per attribute and could not express `members[value eq "42"]`.
 */
sealed interface ScimValue {
    data class Str(
        val value: String,
    ) : ScimValue

    data class Bool(
        val value: Boolean,
    ) : ScimValue

    data class Num(
        val value: Long,
    ) : ScimValue

    data class Complex(
        val attributes: Map<String, ScimValue>,
    ) : ScimValue

    data class MultiValued(
        val values: List<ScimValue>,
    ) : ScimValue

    /** An explicitly cleared attribute — distinct from one that was never sent. */
    data object Null : ScimValue
}

data class ScimResource(
    val schemas: List<String>,
    val attributes: Map<String, ScimValue>,
)

/**
 * Names a value's JSON shape for an error detail. Error details must never echo caller-supplied
 * content — a member entry can be a megabyte of string or nested to the parser's depth cap — so
 * they name the shape that arrived instead of quoting it.
 */
internal fun ScimValue.shapeName(): String =
    when (this) {
        is ScimValue.Str -> "a string"
        is ScimValue.Bool -> "a boolean"
        is ScimValue.Num -> "a number"
        is ScimValue.Complex -> "an object"
        is ScimValue.MultiValued -> "an array"
        ScimValue.Null -> "null"
    }
