package com.kauth.domain.port

import com.kauth.domain.model.PasswordResetToken
import java.time.Instant

/**
 * Output port — persistence for password reset tokens.
 *
 * Implemented by [PostgresPasswordResetTokenRepository].
 * Tokens are keyed by their SHA-256 hash — the raw token lives only in email links.
 */
interface PasswordResetTokenRepository {
    /** Persists a new token record. Returns the saved instance with its DB id. */
    fun create(token: PasswordResetToken): PasswordResetToken

    /** Looks up a token by its SHA-256 hash. Returns null if not found. */
    fun findByTokenHash(hash: String): PasswordResetToken?

    /** Marks a token as used. Idempotent — no-op if already marked. */
    fun markUsed(
        tokenId: Int,
        usedAt: Instant = Instant.now(),
    )

    /**
     * Deletes all unused tokens for a user.
     * Called before issuing a new one (prevents multiple valid reset links in flight)
     * and after a successful reset (cleanup).
     */
    fun deleteByUser(userId: Int)
}
