package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserRepository

/**
 * Rejects a username that duplicates a different user's email, and vice versa.
 *
 * The two namespaces are separately unique, so the database permits a pair that cannot be
 * resolved at sign-in under EITHER mode. Catch it where a human can still fix it.
 *
 * A user whose username *is* their own email is legal and must stay so — that is the shape
 * integrators actually want.
 */
class IdentifierCollisionCheck(
    private val userRepository: UserRepository,
) {
    /**
     * Returns a human-readable reason, or null when the pair is safe.
     *
     * `username` is null when the caller isn't writing a username — the username→email
     * direction is skipped in that case, since the stored username predates this check and may
     * already legitimately (if historically) collide; only the email→username direction runs,
     * because the email itself is always the thing being written.
     */
    fun check(
        tenantId: TenantId,
        username: String?,
        email: String,
        excludingUserId: UserId? = null,
    ): String? {
        if (username != null) {
            userRepository
                .findByEmail(tenantId, username)
                ?.takeIf { it.id != excludingUserId }
                ?.let { return "That username is already in use as another user's email address." }
        }

        userRepository
            .findByUsernameIgnoreCase(tenantId, email)
            ?.takeIf { it.id != excludingUserId }
            ?.let { return "That email address is already in use as another user's username." }

        return null
    }
}
