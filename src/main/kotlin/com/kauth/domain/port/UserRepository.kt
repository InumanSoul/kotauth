package com.kauth.domain.port

import com.kauth.domain.model.User
import java.time.Instant

/**
 * Port (outbound) — defines what the domain needs from user persistence.
 * All queries are scoped by [tenantId] — there are no cross-tenant lookups.
 */
interface UserRepository {
    fun findById(id: Int): User?

    fun findByUsername(
        tenantId: Int,
        username: String,
    ): User?

    fun findByEmail(
        tenantId: Int,
        email: String,
    ): User?

    /** Returns all users in a tenant, optionally filtered by a search term (username/email/name prefix). */
    fun findByTenantId(
        tenantId: Int,
        search: String? = null,
    ): List<User>

    fun save(user: User): User

    /** Updates mutable profile fields (email, fullName, emailVerified, enabled). Username is immutable. */
    fun update(user: User): User

    /**
     * Updates a user's password hash and records the change timestamp.
     * Intentionally separate from [update] to keep the change explicit and auditable.
     * Phase 3b: called by self-service password change and admin force-reset.
     */
    fun updatePassword(
        userId: Int,
        passwordHash: String,
        changedAt: Instant,
    ): User

    fun existsByUsername(
        tenantId: Int,
        username: String,
    ): Boolean

    fun existsByEmail(
        tenantId: Int,
        email: String,
    ): Boolean
}
