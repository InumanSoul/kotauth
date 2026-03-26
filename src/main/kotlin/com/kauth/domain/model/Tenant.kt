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
    val id: TenantId,
    val slug: String,
    val displayName: String,
    val issuerUrl: String?,
    val tokenExpirySeconds: Long = 3600L,
    val refreshTokenExpirySeconds: Long = 86400L,
    val registrationEnabled: Boolean = true,
    val emailVerificationRequired: Boolean = false,
    val securityConfig: SecurityConfig = SecurityConfig(),
    val theme: TenantTheme = TenantTheme.DEFAULT,
    // SMTP fields
    // smtp_password is stored AES-256-GCM encrypted in the DB (see EncryptionService).
    // This field holds the decrypted value at runtime — never persist the raw password.
    val smtpHost: String? = null,
    val smtpPort: Int = 587,
    val smtpUsername: String? = null,
    val smtpPassword: String? = null,
    val smtpFromAddress: String? = null,
    val smtpFromName: String? = null,
    val smtpTlsEnabled: Boolean = true,
    val smtpEnabled: Boolean = false,
    // Session policy
    val maxConcurrentSessions: Int? = null, // null = unlimited
    // Portal UI configuration (loaded from workspace_portal_config via LEFT JOIN)
    val portalConfig: PortalConfig = PortalConfig(),
) {
    /** True for the built-in platform-admin tenant. */
    val isMaster: Boolean get() = slug == MASTER_SLUG

    // Backward-compatible accessors — delegate to securityConfig so existing call sites need no changes.
    val passwordPolicyMinLength: Int get() = securityConfig.passwordMinLength
    val passwordPolicyRequireSpecial: Boolean get() = securityConfig.passwordRequireSpecial
    val passwordPolicyHistoryCount: Int get() = securityConfig.passwordHistoryCount
    val passwordPolicyMaxAgeDays: Int get() = securityConfig.passwordMaxAgeDays
    val passwordPolicyRequireUppercase: Boolean get() = securityConfig.passwordRequireUppercase
    val passwordPolicyRequireNumber: Boolean get() = securityConfig.passwordRequireNumber
    val passwordPolicyBlacklistEnabled: Boolean get() = securityConfig.passwordBlacklistEnabled
    val mfaPolicy: String get() = securityConfig.mfaPolicy

    /** True when SMTP is fully configured and enabled for this tenant. */
    val isSmtpReady: Boolean
        get() = smtpEnabled && !smtpHost.isNullOrBlank() && !smtpFromAddress.isNullOrBlank()

    companion object {
        const val MASTER_SLUG = "master"
    }
}
