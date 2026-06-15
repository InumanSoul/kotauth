package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository

class AdminService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val auditLog: AuditLogPort,
    private val selfServiceService: UserSelfServiceService,
    private val passwordPolicy: PasswordPolicyPort? = null,
) {
    fun updateSmtpConfig(
        slug: String,
        smtpHost: String?,
        smtpPort: Int,
        smtpUsername: String?,
        smtpPassword: String?,
        smtpFromAddress: String?,
        smtpFromName: String?,
        smtpTlsEnabled: Boolean,
        smtpEnabled: Boolean,
    ): AdminResult<Tenant> {
        val tenant =
            tenantRepository.findBySlug(slug)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace '$slug' not found."))

        if (smtpEnabled) {
            if (smtpHost.isNullOrBlank()) {
                return AdminResult.Failure(
                    AdminError.Validation("SMTP host is required when email delivery is enabled."),
                )
            }
            if (smtpFromAddress.isNullOrBlank() || !smtpFromAddress.contains('@')) {
                return AdminResult.Failure(AdminError.Validation("A valid from address is required."))
            }
            if (smtpPort < 1 || smtpPort > 65535) {
                return AdminResult.Failure(AdminError.Validation("SMTP port must be between 1 and 65535."))
            }
        }

        val updated =
            tenantRepository.update(
                tenant.copy(
                    smtpHost = smtpHost?.trim()?.takeIf { it.isNotBlank() },
                    smtpPort = smtpPort,
                    smtpUsername = smtpUsername?.trim()?.takeIf { it.isNotBlank() },
                    smtpPassword = smtpPassword?.takeIf { it.isNotBlank() } ?: tenant.smtpPassword,
                    smtpFromAddress = smtpFromAddress?.trim()?.takeIf { it.isNotBlank() },
                    smtpFromName = smtpFromName?.trim()?.takeIf { it.isNotBlank() },
                    smtpTlsEnabled = smtpTlsEnabled,
                    smtpEnabled = smtpEnabled,
                ),
            )

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_SMTP_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("slug" to slug, "smtpEnabled" to smtpEnabled.toString()),
            ),
        )

        return AdminResult.Success(updated)
    }

    // =========================================================================
    // Admin-initiated password reset
    // =========================================================================

    /**
     * Sends a password-reset email to the user, allowing them to set their
     * own password via the standard self-service flow. Requires SMTP to be
     * configured on the tenant.
     */
    fun sendPasswordResetEmail(
        userId: UserId,
        tenantId: TenantId,
        baseUrl: String,
    ): AdminResult<Unit> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace not found."))
        val user =
            userRepository.findById(userId, tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("User ${userId.value} not found."))
        if (!tenant.isSmtpReady) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "SMTP is not configured for this workspace. Configure SMTP in Settings to use email-based password reset.",
                ),
            )
        }

        return when (
            val result =
                selfServiceService.initiateForgotPassword(
                    user.email,
                    tenant.slug,
                    baseUrl,
                    ipAddress = null,
                )
        ) {
            is SelfServiceResult.Success -> {
                auditLog.record(
                    AuditEvent(
                        tenantId = tenantId,
                        userId = userId,
                        clientId = null,
                        eventType = AuditEventType.ADMIN_USER_PASSWORD_RESET,
                        ipAddress = null,
                        userAgent = null,
                        details = mapOf("username" to user.username, "method" to "email"),
                    ),
                )
                AdminResult.Success(Unit)
            }
            is SelfServiceResult.Failure ->
                AdminResult.Failure(AdminError.Validation(result.error.message))
        }
    }

    /**
     * Admin action: force a user to change their password on next login.
     *
     * Generates a one-time change-password token (24-hour expiry), stamps
     * [RequiredAction.CHANGE_PASSWORD] on the account, and returns the raw
     * token for one-time display in the admin console. The token is never
     * stored or logged in plaintext.
     *
     * On next login, [AuthService] rejects the credentials with
     * [AuthError.PasswordChangeRequired] and the web adapter redirects the
     * user to `/t/{slug}/change-password?token={raw}`.
     *
     * The returned raw token must be shown to the admin exactly once.
     */
    fun setTemporaryPassword(
        userId: UserId,
        tenantId: TenantId,
    ): AdminResult<String> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace not found."))
        if (!tenant.securityConfig.passwordLoginEnabled) {
            return AdminResult.Failure(
                AdminError.Validation("Password sign-in is disabled for this workspace."),
            )
        }
        val user =
            userRepository.findById(userId, tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("User ${userId.value} not found."))

        return when (val result = selfServiceService.initiateForcedPasswordChange(user)) {
            is SelfServiceResult.Success -> {
                auditLog.record(
                    AuditEvent(
                        tenantId = tenantId,
                        userId = userId,
                        clientId = null,
                        eventType = AuditEventType.ADMIN_FORCED_PASSWORD_CHANGE,
                        ipAddress = null,
                        userAgent = null,
                        details = mapOf("username" to user.username),
                    ),
                )
                AdminResult.Success(result.value)
            }
            is SelfServiceResult.Failure ->
                AdminResult.Failure(AdminError.Validation(result.error.message))
        }
    }

    /**
     * Triggers a verification email to be resent for the given user.
     * Delegates to [UserSelfServiceService] to keep the email flow in one place.
     */
    fun resendVerificationEmail(
        userId: UserId,
        tenantId: TenantId,
        baseUrl: String,
    ): AdminResult<Unit> =
        when (val result = selfServiceService.initiateEmailVerification(userId, tenantId, baseUrl)) {
            is SelfServiceResult.Success -> AdminResult.Success(Unit)
            is SelfServiceResult.Failure -> AdminResult.Failure(AdminError.Validation(result.error.message))
        }

    /**
     * Unlocks a user account that was locked due to excessive failed login attempts.
     * Resets the failed attempt counter and clears the lock timestamp.
     */
    fun unlockUser(
        userId: UserId,
        tenantId: TenantId,
    ): AdminResult<Unit> {
        userRepository.findById(userId, tenantId)
            ?: return AdminResult.Failure(AdminError.NotFound("User not found."))
        userRepository.resetFailedLogins(userId)
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.ACCOUNT_UNLOCKED,
                ipAddress = null,
                userAgent = null,
            ),
        )
        return AdminResult.Success(Unit)
    }
}

// =============================================================================
// Result types
// =============================================================================

sealed class AdminResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AdminResult<T>()

    data class Failure(
        val error: AdminError,
    ) : AdminResult<Nothing>()
}

sealed class AdminError(
    val message: String,
) {
    class NotFound(
        message: String,
    ) : AdminError(message)

    class Conflict(
        message: String,
    ) : AdminError(message)

    class Validation(
        message: String,
    ) : AdminError(message)
}
