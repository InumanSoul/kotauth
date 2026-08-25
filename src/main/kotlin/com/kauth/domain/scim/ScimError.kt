package com.kauth.domain.scim

/** RFC 7644 §3.12 scimType values, limited to those this implementation emits. */
@Suppress("EnumEntryName")
enum class ScimErrorType {
    invalidFilter,
    invalidPath,
    invalidValue,
    invalidSyntax,
    mutability,
    uniqueness,
    noTarget,
}

/** A typed parse or apply failure. Carried in `Result.failure`; never thrown across a boundary. */
data class ScimFailure(
    val type: ScimErrorType,
    val detail: String,
) : Exception(detail)
