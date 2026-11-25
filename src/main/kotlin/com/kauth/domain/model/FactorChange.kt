package com.kauth.domain.model

/**
 * A change to a user's sign-in factors that the user is told about by email, so a change they
 * did not make does not go unnoticed. Password changes have their own email.
 */
enum class FactorChange {
    TOTP_ADDED,
    TOTP_REMOVED,
    TOTP_RESET_BY_ADMIN,
    RECOVERY_CODE_USED,
    PASSKEY_ADDED,
    PASSKEY_REMOVED,
    PASSKEY_REMOVED_BY_ADMIN,
    PASSKEYS_RESET_BY_ADMIN,
    IDENTITY_UNLINKED,
    IDENTITY_UNLINKED_BY_ADMIN,
}
