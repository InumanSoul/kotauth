package com.kauth.domain.port

import com.kauth.domain.model.EmailVerificationToken
import java.time.Instant

/**
 * Output port — persistence for email verification tokens.
 *
 * Implemented by [PostgresEmailVerificationTokenRepository].
 * Tokens are keyed by their SHA-256 hash — the raw token lives only in email links.
 */
interface EmailVerificationTokenRepository {
    /** Persists a new token record. Returns the saved instance with its DB id. */
    fun create(token: EmailVerificationToken): EmailVerificationToken

    /** Looks up a token by its SHA-256 hash. Returns null if not found. */
    fun findByTokenHash(hash: String): EmailVerificationToken?

    /** Marks a token as used. Idempotent — no-op if already marked. */
    fun markUsed(
        tokenId: Int,
        usedAt: Instant = Instant.now(),
    )

    /**
     * Deletes all unused (not yet verified) tokens for a user.
     * Called before issuing a new token to prevent accumulation.
     */
    fun deleteUnusedByUser(userId: Int)
}
