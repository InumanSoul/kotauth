package com.kauth.domain.port

import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
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

    /**
     * Looks up a session by refresh token hash regardless of revocation or
     * expiry state. Used for refresh-token replay detection: a hash matching
     * a session revoked with [Session.REVOCATION_REASON_ROTATED] means a
     * rotated token was replayed.
     */
    fun findByRefreshTokenHash(hash: String): Session?

    /** Marks a session as revoked. No-op if already revoked. */
    fun revoke(
        sessionId: SessionId,
        revokedAt: Instant = Instant.now(),
        reason: String? = null,
    )

    /** Revokes all active sessions for a user within a tenant. */
    fun revokeAllForUser(
        tenantId: TenantId,
        userId: UserId,
        revokedAt: Instant = Instant.now(),
    )

    /** Returns all active sessions for a user (for the self-service portal). */
    fun findActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): List<Session>

    /** Returns a session by its DB id, regardless of state. */
    fun findById(id: SessionId): Session?

    /** Revokes all active sessions across all users in a tenant. */
    fun revokeAllForTenant(
        tenantId: TenantId,
        revokedAt: Instant = Instant.now(),
    ): Int

    /** Returns active (non-revoked, non-expired) sessions across all users in a tenant, optionally paginated. */
    fun findActiveByTenant(
        tenantId: TenantId,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
    ): List<Session>

    /** Returns total count of active sessions for [tenantId]. Used for pagination/display. */
    fun countActiveByTenant(tenantId: TenantId): Int

    /**
     * Counts active (non-revoked, non-expired) sessions for a user within a tenant.
     * Used to enforce [Tenant.maxConcurrentSessions].
     */
    fun countActiveByUser(
        tenantId: TenantId,
        userId: UserId,
    ): Int

    /**
     * Revokes the oldest sessions for a user, keeping only [keepNewest] active.
     * Called after login when the session count exceeds the tenant limit.
     */
    fun revokeOldestForUser(
        tenantId: TenantId,
        userId: UserId,
        keepNewest: Int,
    )

    /**
     * Deletes sessions that are both expired and revoked (or expired for over [retentionDays]).
     * Returns the number of rows deleted. Used by the background cleanup job.
     */
    fun deleteExpired(retentionDays: Int = 7): Int

    /**
     * Returns active impersonation sessions whose parent is [parentSessionId].
     * Used to cascade revocation when the admin's own session is revoked.
     */
    fun findActiveByImpersonator(parentSessionId: SessionId): List<Session>

    /**
     * Revokes all active impersonation sessions whose parent is [parentSessionId].
     * No-op if none exist. Returns the number of rows revoked.
     */
    fun revokeAllByImpersonator(
        parentSessionId: SessionId,
        revokedAt: Instant = Instant.now(),
    ): Int
}
