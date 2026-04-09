package com.kauth.domain.port

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import java.time.Instant

/**
 * Port (outbound) — defines what the domain needs from user persistence.
 * All queries are scoped by [tenantId] — there are no cross-tenant lookups.
 */
interface UserRepository {
    fun findById(
        id: UserId,
        tenantId: TenantId,
    ): User?

    fun findByUsername(
        tenantId: TenantId,
        username: String,
    ): User?

    fun findByEmail(
        tenantId: TenantId,
        email: String,
    ): User?

    /** Returns all users matching [ids] scoped to [tenantId] in a single batch query. */
    fun findByIds(
        ids: Collection<UserId>,
        tenantId: TenantId,
    ): List<User>

    /** Returns users in a tenant, optionally filtered by a search term and paginated. */
    fun findByTenantId(
        tenantId: TenantId,
        search: String? = null,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
    ): List<User>

    /** Returns total count of users matching [search] in [tenantId]. Used for pagination. */
    fun countByTenantId(
        tenantId: TenantId,
        search: String? = null,
    ): Long

    fun save(user: User): User

    /** Updates mutable profile fields (email, fullName, emailVerified, enabled). Username is immutable. */
    fun update(user: User): User

    /**
     * Updates a user's password hash and records the change timestamp.
     * Intentionally separate from [update] to keep the change explicit and auditable.
     * Called by self-service password change and admin force-reset.
     */
    fun updatePassword(
        userId: UserId,
        passwordHash: String,
        changedAt: Instant,
    ): User

    fun existsByUsername(
        tenantId: TenantId,
        username: String,
    ): Boolean

    fun existsByEmail(
        tenantId: TenantId,
        email: String,
    ): Boolean

    /**
     * Increments the failed login counter and optionally locks the account until [lockedUntil].
     * Pass `lockedUntil = null` to increment without triggering a lockout.
     */
    fun recordFailedLogin(
        userId: UserId,
        newCount: Int,
        lockedUntil: Instant?,
    )

    /** Clears the failed login counter and removes any active lockout on [userId]. */
    fun resetFailedLogins(userId: UserId)
}
