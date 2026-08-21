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
