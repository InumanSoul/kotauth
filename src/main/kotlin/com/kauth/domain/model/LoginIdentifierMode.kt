package com.kauth.domain.model

/**
 * What a user types in the identifier field of the hosted login form.
 *
 * Per-tenant, stored on [SecurityConfig]. Defaults to [USERNAME] so an upgrade
 * never widens an existing workspace's authentication surface.
 */
enum class LoginIdentifierMode {
    USERNAME,
    EMAIL,

    /** Username is tried first; email second. Never short-circuits — see UserIdentifierResolver. */
    EITHER,
    ;

    companion object {
        /** Parses persisted/form values, falling back to [USERNAME] for anything unrecognised. */
        fun fromStorage(raw: String?): LoginIdentifierMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: USERNAME

        /**
         * Parses a submitted form value, returning null for anything unrecognised instead of
         * coercing to [USERNAME]. Use this where a present-but-invalid value must be rejected
         * rather than silently swapped for a mode the operator did not choose.
         */
        fun parseOrNull(raw: String?): LoginIdentifierMode? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}
