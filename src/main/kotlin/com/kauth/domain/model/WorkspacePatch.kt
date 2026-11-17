package com.kauth.domain.model

/**
 * A partial change to a workspace's settings. A null field leaves the current value untouched.
 * For [issuerUrl] and [defaultLocale] an empty string clears the value.
 */
data class WorkspacePatch(
    val displayName: String? = null,
    val issuerUrl: String? = null,
    val tokenExpirySeconds: Long? = null,
    val refreshTokenExpirySeconds: Long? = null,
    val registrationEnabled: Boolean? = null,
    val emailVerificationRequired: Boolean? = null,
    val passwordLoginEnabled: Boolean? = null,
    val passkeysEnabled: Boolean? = null,
    val magicLinkEnabled: Boolean? = null,
    val emailOtpLoginEnabled: Boolean? = null,
    val passwordMinLength: Int? = null,
    val passwordRequireSpecial: Boolean? = null,
    val passwordRequireUppercase: Boolean? = null,
    val passwordRequireNumber: Boolean? = null,
    val passwordHistoryCount: Int? = null,
    val passwordMaxAgeDays: Int? = null,
    val passwordBlacklistEnabled: Boolean? = null,
    val hibpCheckEnabled: Boolean? = null,
    val mfaPolicy: String? = null,
    val lockoutMaxAttempts: Int? = null,
    val lockoutDurationMinutes: Int? = null,
    val magicLinkTokenTtlMinutes: Int? = null,
    val emailOtpSignupEnabled: Boolean? = null,
    val emailOtpLockoutThreshold: Int? = null,
    val corsAllowCredentials: Boolean? = null,
    val loginIdentifierMode: LoginIdentifierMode? = null,
    val defaultLocale: String? = null,
    val smtp: SmtpPatch? = null,
) {
    /** True when any field handled by [WorkspaceSettingsUpdate] is set. */
    val touchesSettings: Boolean
        get() = this != WorkspacePatch(defaultLocale = defaultLocale, smtp = smtp)

    // One elvis per field; splitting it would only scatter a flat merge.
    @Suppress("CyclomaticComplexMethod")
    fun applyTo(base: WorkspaceSettingsUpdate): WorkspaceSettingsUpdate =
        base.copy(
            displayName = displayName ?: base.displayName,
            issuerUrl = if (issuerUrl == null) base.issuerUrl else issuerUrl.ifBlank { null },
            tokenExpirySeconds = tokenExpirySeconds ?: base.tokenExpirySeconds,
            refreshTokenExpirySeconds = refreshTokenExpirySeconds ?: base.refreshTokenExpirySeconds,
            registrationEnabled = registrationEnabled ?: base.registrationEnabled,
            emailVerificationRequired = emailVerificationRequired ?: base.emailVerificationRequired,
            passwordLoginEnabled = passwordLoginEnabled ?: base.passwordLoginEnabled,
            passkeysEnabled = passkeysEnabled ?: base.passkeysEnabled,
            magicLinkEnabled = magicLinkEnabled ?: base.magicLinkEnabled,
            emailOtpLoginEnabled = emailOtpLoginEnabled ?: base.emailOtpLoginEnabled,
            passwordPolicyMinLength = passwordMinLength ?: base.passwordPolicyMinLength,
            passwordPolicyRequireSpecial = passwordRequireSpecial ?: base.passwordPolicyRequireSpecial,
            passwordPolicyRequireUppercase = passwordRequireUppercase ?: base.passwordPolicyRequireUppercase,
            passwordPolicyRequireNumber = passwordRequireNumber ?: base.passwordPolicyRequireNumber,
            passwordPolicyHistoryCount = passwordHistoryCount ?: base.passwordPolicyHistoryCount,
            passwordPolicyMaxAgeDays = passwordMaxAgeDays ?: base.passwordPolicyMaxAgeDays,
            passwordPolicyBlacklistEnabled = passwordBlacklistEnabled ?: base.passwordPolicyBlacklistEnabled,
            hibpCheckEnabled = hibpCheckEnabled ?: base.hibpCheckEnabled,
            mfaPolicy = mfaPolicy ?: base.mfaPolicy,
            lockoutMaxAttempts = lockoutMaxAttempts ?: base.lockoutMaxAttempts,
            lockoutDurationMinutes = lockoutDurationMinutes ?: base.lockoutDurationMinutes,
            magicLinkTokenTtlMinutes = magicLinkTokenTtlMinutes ?: base.magicLinkTokenTtlMinutes,
            emailOtpSignupEnabled = emailOtpSignupEnabled ?: base.emailOtpSignupEnabled,
            emailOtpLockoutThreshold = emailOtpLockoutThreshold ?: base.emailOtpLockoutThreshold,
            corsAllowCredentials = corsAllowCredentials ?: base.corsAllowCredentials,
            loginIdentifierMode = loginIdentifierMode ?: base.loginIdentifierMode,
        )
}

/**
 * A partial change to a workspace's SMTP settings. A null field keeps the current value; for
 * [host], [username] and [fromName] an empty string clears it. [password] is write-only and is
 * only replaced by a non-blank value.
 */
data class SmtpPatch(
    val enabled: Boolean? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val fromAddress: String? = null,
    val fromName: String? = null,
    val tlsEnabled: Boolean? = null,
)
