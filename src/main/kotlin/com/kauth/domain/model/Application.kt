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
