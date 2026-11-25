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
    // Magic-link expiry window, in minutes. Read it through [magicLinkTtlMinutes].
    val magicLinkTokenTtlMinutes: Int = 15,
    // Disabling password login affects only the password flow; magic-link and
    // social providers are governed by their own per-tenant config.
    val passwordLoginEnabled: Boolean = true,
    // send-otp does NOT create a user when this is false; the response is still uniform.
    val emailOtpSignupEnabled: Boolean = false,
    // Cross-challenge failed-OTP threshold. 0 disables.
    val emailOtpLockoutThreshold: Int = 5,
    val emailOtpLoginEnabled: Boolean = false,
    // Which identifier the hosted login form accepts. USERNAME preserves pre-1.24 behaviour.
    val loginIdentifierMode: LoginIdentifierMode = LoginIdentifierMode.USERNAME,
    /** Users may change their own username in the account portal. Off: only administrators can. */
    val usernameChangeEnabled: Boolean = false,
) {
    val isLockoutEnabled: Boolean get() = lockoutMaxAttempts > 0

    /** The window a magic link is actually issued with, so the emails and pages can quote it. */
    val magicLinkTtlMinutes: Int get() = magicLinkTokenTtlMinutes.coerceIn(MAGIC_LINK_TTL_MINUTES)
}

val MAGIC_LINK_TTL_MINUTES = 1..1440

/** Failed email-code challenges before the account locks; 0 turns the lockout off. */
val EMAIL_OTP_LOCKOUT_THRESHOLD = 0..50
