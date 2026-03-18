package com.kauth.domain.model

/**
 * Decoded claims from a verified access token.
 * Returned by [TokenPort.decodeAccessToken] for use in introspection and userinfo.
 *
 * [sub] is the user ID (for user tokens) or client_id (for M2M tokens).
 *
 * Phase 3c: [realmRoles] holds tenant-scoped role names,
 * [resourceRoles] maps clientId → list of client-scoped role names.
 */
data class AccessTokenClaims(
    val sub: String,
    val iss: String,
    val aud: String,
    val tenantId: Int,
    val username: String?,
    val email: String?,
    val scopes: List<String>,
    val issuedAt: Long,
    val expiresAt: Long,
    val realmRoles: List<String> = emptyList(),
    val resourceRoles: Map<String, List<String>> = emptyMap(),
)
