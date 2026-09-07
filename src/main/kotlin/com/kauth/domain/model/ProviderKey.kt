package com.kauth.domain.model

/**
 * The identity of a social / OIDC provider, as an open string rather than a closed set.
 *
 * The pattern is load-bearing: the key appears in the callback path, so it must be
 * URL-safe, and it must fit the existing varchar(32) provider columns.
 */
@JvmInline
value class ProviderKey private constructor(
    val value: String,
) {
    companion object {
        private val PATTERN = Regex("^[a-z0-9-]{1,32}$")

        val GOOGLE = ProviderKey("google")
        val GITHUB = ProviderKey("github")

        /** The keys bound to compiled-in adapters. Everything else is an OIDC provider. */
        val RESERVED = setOf(GOOGLE, GITHUB)

        fun of(value: String): ProviderKey? = value.takeIf { PATTERN.matches(it) }?.let { ProviderKey(it) }
    }
}
