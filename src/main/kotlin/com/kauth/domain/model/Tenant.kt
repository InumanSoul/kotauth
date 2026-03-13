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
 *
 * Phase 3b additions:
 *   - SMTP fields for transactional email (verification, password reset).
 *   - maxConcurrentSessions: optional cap on per-user active sessions (null = unlimited).
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
    // Phase 3c: expanded password policy
    val passwordPolicyHistoryCount: Int       = 0,   // 0 = disabled
    val passwordPolicyMaxAgeDays: Int         = 0,   // 0 = never expires
    val passwordPolicyRequireUppercase: Boolean = false,
    val passwordPolicyRequireNumber: Boolean   = false,
    val passwordPolicyBlacklistEnabled: Boolean = false,
    val theme: TenantTheme                    = TenantTheme.DEFAULT,

    // ---- SMTP (Phase 3b) ----
    // smtp_password is stored AES-256-GCM encrypted in the DB (see EncryptionService).
    // This field holds the decrypted value at runtime — never persist the raw password.
    val smtpHost: String?        = null,
    val smtpPort: Int            = 587,
    val smtpUsername: String?    = null,
    val smtpPassword: String?    = null,
    val smtpFromAddress: String? = null,
    val smtpFromName: String?    = null,
    val smtpTlsEnabled: Boolean  = true,
    val smtpEnabled: Boolean     = false,

    // ---- MFA policy (Phase 3c) ----
    // 'optional' = users can opt-in; 'required' = all users must enroll;
    // 'required_admins' = only admin-role users must enroll
    val mfaPolicy: String = "optional",

    // ---- Session policy (Phase 3b) ----
    val maxConcurrentSessions: Int? = null   // null = unlimited
) {
    /** True for the built-in platform-admin tenant. */
    val isMaster: Boolean get() = slug == MASTER_SLUG

    /** True when SMTP is fully configured and enabled for this tenant. */
    val isSmtpReady: Boolean
        get() = smtpEnabled && !smtpHost.isNullOrBlank() && !smtpFromAddress.isNullOrBlank()

    companion object {
        const val MASTER_SLUG = "master"
    }
}
