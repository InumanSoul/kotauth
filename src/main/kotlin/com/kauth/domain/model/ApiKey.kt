package com.kauth.domain.model

import java.time.Instant

/**
 * Represents a machine-to-machine API key for REST API access.
 *
 * The raw key value is NEVER stored here — only the prefix (for display) and the
 * SHA-256 hash (for verification). The plaintext is generated once by [ApiKeyService]
 * and returned to the caller; subsequent calls can only verify or revoke.
 *
 * Scopes follow the pattern `resource:action` (e.g. `users:read`, `roles:write`).
 * An empty scope list means no access — valid scopes are defined in [ApiScope].
 */
data class ApiKey(
    val id: Int? = null,
    val tenantId: TenantId,
    val name: String,
    /** First 8 chars of the raw key — shown in the admin UI as a hint (e.g. "kauth_my…"). */
    val keyPrefix: String,
    /** SHA-256 hex digest of the full raw key — used for lookup on every request. */
    val keyHash: String,
    val scopes: List<String>,
    val expiresAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

/**
 * Canonical scope strings for the REST API.
 * Routes validate that the authenticating key holds the required scope before
 * allowing access. Scopes are additive — a key may hold multiple.
 */
object ApiScope {
    const val USERS_READ = "users:read"
    const val USERS_WRITE = "users:write"
    const val ROLES_READ = "roles:read"
    const val ROLES_WRITE = "roles:write"
    const val GROUPS_READ = "groups:read"
    const val GROUPS_WRITE = "groups:write"
    const val APPLICATIONS_READ = "applications:read"
    const val APPLICATIONS_WRITE = "applications:write"
    const val SESSIONS_READ = "sessions:read"
    const val SESSIONS_WRITE = "sessions:write"
    const val AUDIT_LOGS_READ = "audit_logs:read"

    val ALL =
        listOf(
            USERS_READ,
            USERS_WRITE,
            ROLES_READ,
            ROLES_WRITE,
            GROUPS_READ,
            GROUPS_WRITE,
            APPLICATIONS_READ,
            APPLICATIONS_WRITE,
            SESSIONS_READ,
            SESSIONS_WRITE,
            AUDIT_LOGS_READ,
        )
}
