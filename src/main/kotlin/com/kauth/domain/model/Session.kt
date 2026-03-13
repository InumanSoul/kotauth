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
 */
data class Session(
    val id: Int? = null,
    val tenantId: Int,
    val userId: Int?,          // null for M2M (client_credentials)
    val clientId: Int?,
    val accessTokenHash: String,
    val refreshTokenHash: String?,
    val scopes: String = "openid",
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant,
    val refreshExpiresAt: Instant? = null,
    val lastActivityAt: Instant = Instant.now(),
    val revokedAt: Instant? = null
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isRevoked: Boolean get() = revokedAt != null
    val isActive: Boolean get() = !isExpired && !isRevoked
}
