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
     * Returns a human-readable reason, or null when the pair is safe. Checks BOTH directions:
     * `username` cannot equal a different user's email, and `email` cannot equal a different
     * user's username.
     *
     * Use this whenever a username is actually being written (created or renamed). When the
     * caller isn't writing a username, use [checkEmailOnly] instead — a nullable `username`
     * parameter here would let a caller silently skip the username→email direction with no
     * diagnostic.
     */
    fun check(
        tenantId: TenantId,
        username: String,
        email: String,
        excludingUserId: UserId? = null,
    ): String? {
        userRepository
            .findByEmail(tenantId, username)
            ?.takeIf { it.id != excludingUserId }
            ?.let { return "That username is already in use as another user's email address." }

        return checkEmailOnly(tenantId, email, excludingUserId)
    }

    /**
     * Runs only the email→username direction: rejects `email` when it equals a different user's
     * username. Use this when a username isn't being written — e.g. a profile update that only
     * touches `fullName`, where re-checking the stored username against every other user's email
     * would reject users whose username predates this check.
     */
    fun checkEmailOnly(
        tenantId: TenantId,
        email: String,
        excludingUserId: UserId? = null,
    ): String? {
        userRepository
            .findByUsernameIgnoreCase(tenantId, email)
            ?.takeIf { it.id != excludingUserId }
            ?.let { return "That email address is already in use as another user's username." }

        return null
    }
}
