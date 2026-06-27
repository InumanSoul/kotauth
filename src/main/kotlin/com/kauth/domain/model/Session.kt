package com.kauth.domain.model

import java.time.Instant

/**
 * A persisted user session — the server-side record of an issued token set.
 *
 * Sessions are created on every successful authentication (login, auth code
 * exchange, refresh). They are revoked on logout or explicit revocation.
 *
 * Token hashes (SHA-256) are stored, never the raw token strings.
 * Refresh token rotation: each refresh creates a new Session and revokes the old one.
 *
 * [userId] is null for client_credentials sessions (machine-to-machine).
 *
 * [impersonatorSessionId] is set on impersonation sessions and points to the
 * admin's own session row. When non-null, [userId] is the impersonated user
 * and the admin's identity lives one hop away through the parent session.
 */
data class Session(
    val id: SessionId? = null,
    val tenantId: TenantId,
    val userId: UserId?,
    val clientId: ApplicationId?,
    val accessTokenHash: String,
    val refreshTokenHash: String?,
    val scopes: String = "openid",
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant,
    val refreshExpiresAt: Instant? = null,
    val lastActivityAt: Instant = Instant.now(),
    val revokedAt: Instant? = null,
    val revocationReason: String? = null,
    val impersonatorSessionId: SessionId? = null,
    val resources: List<String> = emptyList(),
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isRevoked: Boolean get() = revokedAt != null
    val isActive: Boolean get() = !isExpired && !isRevoked
    val isImpersonation: Boolean get() = impersonatorSessionId != null

    companion object {
        /**
         * Revocation reason set when a session is superseded by refresh-token
         * rotation. Presenting the rotated refresh token again is treated as
         * theft (OAuth 2.0 Security BCP) — unlike logout/admin revocation.
         */
        const val REVOCATION_REASON_ROTATED = "rotated"
    }
}
