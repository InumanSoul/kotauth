package com.kauth.domain.port

import com.kauth.domain.model.Session
import java.time.Instant

/**
 * Port — persisted session storage.
 * Implemented by [PostgresSessionRepository].
 */
interface SessionRepository {
    /** Persists a new session record. Returns the saved session with its DB id. */
    fun save(session: Session): Session

    /** Looks up an active (non-revoked, non-expired) session by access token hash. */
    fun findActiveByAccessTokenHash(hash: String): Session?

    /** Looks up an active session by refresh token hash. */
    fun findActiveByRefreshTokenHash(hash: String): Session?

    /** Marks a session as revoked. No-op if already revoked. */
    fun revoke(sessionId: Int, revokedAt: Instant = Instant.now())

    /** Revokes all active sessions for a user within a tenant. */
    fun revokeAllForUser(tenantId: Int, userId: Int, revokedAt: Instant = Instant.now())

    /** Returns all active sessions for a user (for the self-service portal). */
    fun findActiveByUser(tenantId: Int, userId: Int): List<Session>

    /** Returns a session by its DB id, regardless of state. */
    fun findById(id: Int): Session?

    /** Returns all active (non-revoked, non-expired) sessions across all users in a tenant. */
    fun findActiveByTenant(tenantId: Int): List<Session>
}
