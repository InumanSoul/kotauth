package com.kauth.domain.model

/**
 * An OAuth2 / OIDC client registered within a Tenant (Workspace).
 *
 * Public-facing term: Application.
 * Internal domain term: Client (mirrors RFC 6749 terminology).
 *
 * Access types follow the Keycloak model:
 *   PUBLIC       — browser / SPA / mobile apps; no client secret required
 *   CONFIDENTIAL — server-side apps that can safely store a secret
 *   BEARER_ONLY  — resource servers that only validate tokens, never initiate flows
 */
data class Application(
    val id: Int,
    val tenantId: Int,
    val clientId: String,
    val name: String,
    val description: String?,
    val accessType: AccessType,
    val enabled: Boolean,
    val redirectUris: List<String> = emptyList(),
    /** Per-client override for access token lifetime in seconds. Null means use tenant/server default. */
    val tokenExpiryOverride: Int? = null,
)

enum class AccessType(
    val value: String,
) {
    PUBLIC("public"),
    CONFIDENTIAL("confidential"),
    BEARER_ONLY("bearer_only"),
    ;

    val label: String get() =
        when (this) {
            PUBLIC -> "Public"
            CONFIDENTIAL -> "Confidential"
            BEARER_ONLY -> "Bearer Only"
        }

    companion object {
        fun fromValue(value: String): AccessType = entries.firstOrNull { it.value == value } ?: PUBLIC
    }
}
