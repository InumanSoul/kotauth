package com.kauth.domain.model

import java.time.Instant

/**
 * Represents a machine-to-machine API key for REST API access.
 *
 * The raw key value is NEVER stored here — only the prefix (for display) and the
 * SHA-256 hash (for verification). The plaintext is generated once by ApiKeyService
 * (domain/service/) and returned to the caller; subsequent calls can only verify or revoke.
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
    /** Non-null when provisioned via `KAUTH_BOOTSTRAP_API_KEYS` — admin UI marks these read-only. */
    val bootstrapName: String? = null,
    /** SCIM wire dialect this key's client speaks; `rfc` is the spec-canonical pass-through. */
    val scimDialect: String = DEFAULT_SCIM_DIALECT,
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        const val DEFAULT_SCIM_DIALECT = "rfc"
    }
}

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
    const val USER_ATTRIBUTES_READ = "user_attributes:read"
    const val USER_ATTRIBUTES_WRITE = "user_attributes:write"
    const val CLAIM_MAPPERS_READ = "claim_mappers:read"
    const val CLAIM_MAPPERS_WRITE = "claim_mappers:write"

    /** List identity provider configurations. The client secret is never returned. */
    const val IDENTITY_PROVIDERS_READ = "identity_providers:read"

    /** Create, update and delete identity provider configurations. */
    const val IDENTITY_PROVIDERS_WRITE = "identity_providers:write"

    /** Master-tenant only — export a workspace as an encrypted backup. */
    const val TENANTS_EXPORT = "tenants:export"

    /** Master-tenant only — import an encrypted backup as a new workspace. */
    const val TENANTS_IMPORT = "tenants:import"

    /** Send an email OTP challenge for an applicant (find-or-create user). */
    const val AUTH_SEND_OTP = "auth:send-otp"

    /** Verify an email OTP challenge and exchange it for an authorization code. */
    const val AUTH_VERIFY_OTP = "auth:verify-otp"

    /** Read tenant metadata, sign-in methods, and security/MFA policy. SMTP credentials excluded. */
    const val WORKSPACE_READ = "workspace:read"

    /** List webhook endpoints (never exposes the signing secret). */
    const val WEBHOOKS_READ = "webhooks:read"

    /** Create/delete webhook endpoints. */
    const val WEBHOOKS_WRITE = "webhooks:write"

    /** List and retrieve resource servers (RFC 8707 resource indicators). */
    const val RESOURCE_SERVERS_READ = "resource_servers:read"

    /** Create, update, delete resource servers; manage client authorization edges. */
    const val RESOURCE_SERVERS_WRITE = "resource_servers:write"

    /** List API keys for the workspace (never exposes the key hash or raw value). */
    const val API_KEYS_READ = "api_keys:read"

    /** Create and revoke API keys — meta-circular: includes the authenticating key itself. */
    const val API_KEYS_WRITE = "api_keys:write"

    /**
     * Full access to the SCIM 2.0 provisioning surface. Deliberately not split into
     * read/write: a provisioning connector needs both to function, so a read-only key would
     * connect successfully and only fail once it attempts its first write.
     */
    const val SCIM = "scim"

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
            USER_ATTRIBUTES_READ,
            USER_ATTRIBUTES_WRITE,
            CLAIM_MAPPERS_READ,
            CLAIM_MAPPERS_WRITE,
            IDENTITY_PROVIDERS_READ,
            IDENTITY_PROVIDERS_WRITE,
            TENANTS_EXPORT,
            TENANTS_IMPORT,
            AUTH_SEND_OTP,
            AUTH_VERIFY_OTP,
            WORKSPACE_READ,
            WEBHOOKS_READ,
            WEBHOOKS_WRITE,
            RESOURCE_SERVERS_READ,
            RESOURCE_SERVERS_WRITE,
            API_KEYS_READ,
            API_KEYS_WRITE,
            SCIM,
        )
}
