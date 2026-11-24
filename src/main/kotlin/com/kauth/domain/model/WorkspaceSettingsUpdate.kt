package com.kauth.domain.model

data class WorkspaceSettingsUpdate(
    val displayName: String,
    val issuerUrl: String?,
    val tokenExpirySeconds: Long,
    val refreshTokenExpirySeconds: Long,
    val registrationEnabled: Boolean,
    val emailVerificationRequired: Boolean,
    val passwordPolicyMinLength: Int,
    val passwordPolicyRequireSpecial: Boolean,
    val passwordPolicyRequireUppercase: Boolean,
    val passwordPolicyRequireNumber: Boolean,
    val passwordPolicyHistoryCount: Int,
    val passwordPolicyMaxAgeDays: Int,
    val passwordPolicyBlacklistEnabled: Boolean,
    val mfaPolicy: String,
    val lockoutMaxAttempts: Int,
    val lockoutDurationMinutes: Int,
    val corsAllowCredentials: Boolean,
    val hibpCheckEnabled: Boolean,
    val magicLinkEnabled: Boolean,
    val magicLinkTokenTtlMinutes: Int,
    val passwordLoginEnabled: Boolean,
    val emailOtpSignupEnabled: Boolean,
    val emailOtpLockoutThreshold: Int,
    val emailOtpLoginEnabled: Boolean,
    val passkeysEnabled: Boolean,
    val loginIdentifierMode: LoginIdentifierMode,
    /** Active sessions a user may hold in one application; null means no limit. */
    val maxConcurrentSessions: Int? = null,
    /** See [Tenant.publicBaseUrl]; null or blank clears it, so every caller must pass the current value. */
    val publicBaseUrl: String?,
) {
    companion object {
        fun from(tenant: Tenant): WorkspaceSettingsUpdate {
            val s = tenant.securityConfig
            return WorkspaceSettingsUpdate(
                displayName = tenant.displayName,
                issuerUrl = tenant.issuerUrl,
                publicBaseUrl = tenant.publicBaseUrl,
                tokenExpirySeconds = tenant.tokenExpirySeconds,
                refreshTokenExpirySeconds = tenant.refreshTokenExpirySeconds,
                registrationEnabled = tenant.registrationEnabled,
                emailVerificationRequired = tenant.emailVerificationRequired,
                passwordPolicyMinLength = s.passwordMinLength,
                passwordPolicyRequireSpecial = s.passwordRequireSpecial,
                passwordPolicyRequireUppercase = s.passwordRequireUppercase,
                passwordPolicyRequireNumber = s.passwordRequireNumber,
                passwordPolicyHistoryCount = s.passwordHistoryCount,
                passwordPolicyMaxAgeDays = s.passwordMaxAgeDays,
                passwordPolicyBlacklistEnabled = s.passwordBlacklistEnabled,
                mfaPolicy = s.mfaPolicy,
                lockoutMaxAttempts = s.lockoutMaxAttempts,
                lockoutDurationMinutes = s.lockoutDurationMinutes,
                corsAllowCredentials = s.corsAllowCredentials,
                hibpCheckEnabled = s.hibpCheckEnabled,
                magicLinkEnabled = s.magicLinkEnabled,
                magicLinkTokenTtlMinutes = s.magicLinkTokenTtlMinutes,
                passwordLoginEnabled = s.passwordLoginEnabled,
                emailOtpSignupEnabled = s.emailOtpSignupEnabled,
                emailOtpLockoutThreshold = s.emailOtpLockoutThreshold,
                emailOtpLoginEnabled = s.emailOtpLoginEnabled,
                passkeysEnabled = tenant.passkeysEnabled,
                loginIdentifierMode = s.loginIdentifierMode,
                maxConcurrentSessions = tenant.maxConcurrentSessions,
            )
        }
    }
}
