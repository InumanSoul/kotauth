package com.kauth.domain.model

import java.time.Instant

/**
 * Core domain entity representing an authenticated user.
 * This class has zero dependencies on any framework (Ktor, Exposed, etc.).
 *
 * [tenantId] scopes this user to exactly one tenant. A user cannot
 * authenticate against a different tenant's clients.
 *
 * Phase 3b addition:
 *   - lastPasswordChangeAt: recorded on every password change (self-service or admin reset).
 *     All existing sessions are revoked whenever this is updated.
 */
data class User(
    val id: Int? = null,
    val tenantId: Int,
    val username: String,
    val email: String,
    val fullName: String,
    val passwordHash: String,
    val emailVerified: Boolean         = false,
    val enabled: Boolean               = true,
    val lastPasswordChangeAt: Instant? = null,
    // Phase 3c: MFA — true once the user has a verified TOTP enrollment
    val mfaEnabled: Boolean            = false
)
