package com.kauth.domain.model

/**
 * Per-tenant security policy configuration.
 *
 * Stored in `tenant_security_config` (1:1 with tenant).
 * Composed into [Tenant] as a value object — callers access it via `tenant.securityConfig`.
 *
 * Covers password policy, MFA policy, and account lockout.
 */
data class SecurityConfig(
    // Password policy
    val passwordMinLength: Int = 8,
    val passwordRequireSpecial: Boolean = false,
    val passwordRequireUppercase: Boolean = false,
    val passwordRequireNumber: Boolean = false,
    val passwordHistoryCount: Int = 0,
    val passwordMaxAgeDays: Int = 0,
    val passwordBlacklistEnabled: Boolean = false,
    // MFA policy
    val mfaPolicy: String = "optional",
    // Account lockout
    val lockoutMaxAttempts: Int = 0,
    val lockoutDurationMinutes: Int = 15,
    // CORS
    val corsAllowCredentials: Boolean = false,
    // Breach detection — block passwords that appear in HIBP's breach corpus
    val hibpCheckEnabled: Boolean = false,
    val magicLinkEnabled: Boolean = false,
    val magicLinkTokenTtlMinutes: Int = 15,
    // Disabling password login affects only the password flow; magic-link and
    // social providers are governed by their own per-tenant config.
    val passwordLoginEnabled: Boolean = true,
) {
    val isLockoutEnabled: Boolean get() = lockoutMaxAttempts > 0
}
