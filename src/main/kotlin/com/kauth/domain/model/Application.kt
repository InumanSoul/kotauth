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
    val id: ApplicationId,
    val tenantId: TenantId,
    val clientId: String,
    val name: String,
    val description: String?,
    val accessType: AccessType,
    val enabled: Boolean,
    val redirectUris: List<String> = emptyList(),
    /** Grants this client may use. Empty is valid only for BEARER_ONLY. */
    val grantTypes: Set<GrantType> = emptySet(),
    /** Per-client override for access token lifetime in seconds. Null means use tenant/server default. */
    val tokenExpiryOverride: Int? = null,
    /** Public-facing URL the launcher tile navigates to. Null = app omitted from the launcher. */
    val launcherUrl: String? = null,
    /** Optional icon URL for the launcher tile. Null falls back to a first-letter SVG. */
    val iconUrl: String? = null,
    /** Admin-controlled toggle to hide a configured app from the launcher without unsetting the URL. */
    val launcherVisible: Boolean = true,
    /** Admin-controlled sort order in the launcher; ties broken by name. */
    val launcherDisplayOrder: Int = 0,
    /**
     * Custom access-token `aud` claim. Null falls back to [clientId], then the
     * tenant slug. Lets one client mint tokens for a resource server whose
     * identifier differs from the client_id.
     */
    val audience: String? = null,
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

/**
 * Grants a client is registered to use (RFC 7591 `grant_types`).
 * Members mirror exactly what the token endpoint dispatches on.
 */
enum class GrantType(
    val value: String,
) {
    AUTHORIZATION_CODE("authorization_code"),
    CLIENT_CREDENTIALS("client_credentials"),
    REFRESH_TOKEN("refresh_token"),
    ;

    val label: String get() =
        when (this) {
            AUTHORIZATION_CODE -> "Authorization Code"
            CLIENT_CREDENTIALS -> "Client Credentials"
            REFRESH_TOKEN -> "Refresh Token"
        }

    companion object {
        // Unknown grants return null rather than defaulting: silently substituting a permission is a security bug.
        fun fromValue(value: String): GrantType? = entries.firstOrNull { it.value == value }

        fun defaultsFor(accessType: AccessType): Set<GrantType> =
            when (accessType) {
                AccessType.CONFIDENTIAL -> setOf(AUTHORIZATION_CODE, CLIENT_CREDENTIALS, REFRESH_TOKEN)
                AccessType.PUBLIC -> setOf(AUTHORIZATION_CODE, REFRESH_TOKEN)
                AccessType.BEARER_ONLY -> emptySet()
            }
    }
}
