package com.kauth.domain.port

import com.kauth.domain.model.Tenant

/**
 * Port for password policy enforcement (Phase 3c).
 *
 * Provides validation against tenant-configured password policies including
 * complexity requirements, history checks, and blacklist lookups.
 */
interface PasswordPolicyPort {

    /**
     * Validates a raw password against the full tenant password policy.
     * Returns null if valid, or a human-readable error message if invalid.
     */
    fun validate(rawPassword: String, tenant: Tenant, userId: Int? = null): String?

    /**
     * Records a password hash in the user's history.
     * Should be called after every successful password change.
     */
    fun recordPasswordHistory(userId: Int, tenantId: Int, passwordHash: String)

    /**
     * Checks if the given raw password matches any of the user's last N passwords.
     */
    fun isInHistory(userId: Int, tenantId: Int, rawPassword: String, historyCount: Int): Boolean

    /**
     * Checks if the password appears in the blacklist (global + tenant-specific).
     */
    fun isBlacklisted(rawPassword: String, tenantId: Int): Boolean
}
