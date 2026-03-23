package com.kauth.domain.model

import java.time.Instant

/**
 * Core domain entity representing an authenticated user.
 * This class has zero dependencies on any framework (Ktor, Exposed, etc.).
 *
 * [tenantId] scopes this user to exactly one tenant. A user cannot
 * authenticate against a different tenant's clients.
 */
data class User(
    val id: UserId? = null,
    val tenantId: TenantId,
    val username: String,
    val email: String,
    val fullName: String,
    val passwordHash: String,
    val emailVerified: Boolean = false,
    val enabled: Boolean = true,
    val lastPasswordChangeAt: Instant? = null,
    val mfaEnabled: Boolean = false,
    val createdAt: Instant? = null,
)
