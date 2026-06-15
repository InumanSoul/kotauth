package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository

class AdminCredentialService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val auditLog: AuditLogPort,
    private val selfServiceService: UserSelfServiceService,
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

    fun resendVerificationEmail(
        userId: UserId,
        tenantId: TenantId,
        baseUrl: String,
    ): AdminResult<Unit> =
        when (val result = selfServiceService.initiateEmailVerification(userId, tenantId, baseUrl)) {
            is SelfServiceResult.Success -> AdminResult.Success(Unit)
            is SelfServiceResult.Failure -> AdminResult.Failure(AdminError.Validation(result.error.message))
        }

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
