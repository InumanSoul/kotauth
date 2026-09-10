package com.kauth.domain.scim

/**
 * RFC 7644 §3.12 scimType values, limited to those this implementation emits.
 *
 * The entries are lower camel case because they are the wire values themselves — the RFC defines
 * `invalidFilter`, not `INVALID_FILTER` — so renaming them would mean a mapping table that exists
 * only to satisfy a naming rule. Both the compiler's and detekt's rules are suppressed here.
 */
@Suppress("EnumEntryName", "EnumNaming")
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
