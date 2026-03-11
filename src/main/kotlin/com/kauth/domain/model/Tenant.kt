package com.kauth.domain.model

/**
 * Core domain entity representing an isolated tenant (Authorization Server).
 *
 * A Tenant owns its own user directory, clients, roles, and token settings.
 * Nothing crosses tenant boundaries — users from Tenant A cannot authenticate
 * against Tenant B's clients.
 *
 * The 'master' tenant is reserved for platform administrators who manage
 * other tenants via the admin console.
 */
data class Tenant(
    val id: Int,
    val slug: String,
    val displayName: String,
    val issuerUrl: String?,
    val tokenExpirySeconds: Long              = 3600L,
    val refreshTokenExpirySeconds: Long       = 86400L,
    val registrationEnabled: Boolean          = true,
    val emailVerificationRequired: Boolean    = false,
    val passwordPolicyMinLength: Int          = 8,
    val passwordPolicyRequireSpecial: Boolean = false,
    val theme: TenantTheme                    = TenantTheme.DEFAULT
) {
    /** True for the built-in platform-admin tenant. */
    val isMaster: Boolean get() = slug == MASTER_SLUG

    companion object {
        const val MASTER_SLUG = "master"
    }
}
