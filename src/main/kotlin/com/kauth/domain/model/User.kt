package com.kauth.domain.model

import java.time.Instant

/**
 * Core domain entity representing an authenticated user.
 * This class has zero dependencies on any framework (Ktor, Exposed, etc.).
 *
 * [tenantId] scopes this user to exactly one tenant. A user cannot
 * authenticate against a different tenant's clients.
 */
/**
 * Actions a user must complete before normal authentication is allowed.
 *
 * Stored as `text[]` in PostgreSQL — new values can be added without a migration.
 * The auth flow checks this set before password verification and short-circuits
 * with a specific error for each action type.
 */
enum class RequiredAction {
    /** User was created via invite and must set a password before logging in. */
    SET_PASSWORD,
}

data class User(
    val id: UserId? = null,
    val tenantId: TenantId,
    val username: String,
    val email: String,
    val fullName: String,
    val passwordHash: String,
    val emailVerified: Boolean = false,
    val enabled: Boolean = true,
    val requiredActions: Set<RequiredAction> = emptySet(),
    val lastPasswordChangeAt: Instant? = null,
    val mfaEnabled: Boolean = false,
    val failedLoginAttempts: Int = 0,
    val lockedUntil: Instant? = null,
    val createdAt: Instant? = null,
) {
    /** True when the account is currently locked out (lockout window has not yet expired). */
    val isLocked: Boolean get() = lockedUntil != null && lockedUntil.isAfter(Instant.now())

    companion object {
        /** Sentinel password hash for users who have not yet set a password (invite flow, social login). */
        const val SENTINEL_PASSWORD_HASH = "!"
    }
}
