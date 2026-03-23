package com.kauth.domain.port

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId

/**
 * Port for password policy enforcement.
 *
 * Provides validation against tenant-configured password policies including
 * complexity requirements, history checks, and blacklist lookups.
 */
interface PasswordPolicyPort {
    /**
     * Validates a raw password against the full tenant password policy.
     * Returns null if valid, or a human-readable error message if invalid.
     */
    fun validate(
        rawPassword: String,
        tenant: Tenant,
        userId: UserId? = null,
    ): String?

    /**
     * Records a password hash in the user's history.
     * Should be called after every successful password change.
     */
    fun recordPasswordHistory(
        userId: UserId,
        tenantId: TenantId,
        passwordHash: String,
    )

    /**
     * Checks if the given raw password matches any of the user's last N passwords.
     */
    fun isInHistory(
        userId: UserId,
        tenantId: TenantId,
        rawPassword: String,
        historyCount: Int,
    ): Boolean

    /**
     * Checks if the password appears in the blacklist (global + tenant-specific).
     */
    fun isBlacklisted(
        rawPassword: String,
        tenantId: TenantId,
    ): Boolean
}
