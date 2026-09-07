package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.EmailVerificationToken
import com.kauth.domain.model.PasswordResetToken
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenPurpose
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.EmailPort
import com.kauth.domain.port.EmailVerificationTokenRepository
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.port.PasswordResetTokenRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.SecureTokens
import com.kauth.domain.util.sha256Hex
import com.kauth.domain.util.validatePasswordPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

class CredentialFlowService(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val sessionRepository: SessionRepository,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val evTokenRepo: EmailVerificationTokenRepository,
    private val prTokenRepo: PasswordResetTokenRepository,
    private val emailPort: EmailPort,
    private val passwordPolicy: PasswordPolicyPort? = null,
    private val emailScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val log = LoggerFactory.getLogger(CredentialFlowService::class.java)

    private companion object {
        const val EMAIL_VERIFICATION_TTL_SECONDS = 24 * 3600L
        const val PASSWORD_RESET_TTL_SECONDS = 3600L
        const val INVITE_TTL_SECONDS = 72 * 3600L
        const val TEMP_PASSWORD_TTL_SECONDS = 24 * 3600L
    }

    fun initiateEmailVerification(
        userId: UserId,
        tenantId: TenantId,
        baseUrl: String,
    ): SelfServiceResult<Unit> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("Workspace not found."))
        val user =
            userRepository.findById(userId, tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        if (!tenant.isSmtpReady) {
            return SelfServiceResult.Failure(
                SelfServiceError.SmtpNotConfigured(
                    "Email delivery is not configured for this workspace.",
                ),
            )
        }

        if (user.emailVerified) {
            return SelfServiceResult.Success(Unit)
        }

        val (rawToken, tokenHash) = generateToken()

        evTokenRepo.deleteUnusedByUser(userId)
        evTokenRepo.create(
            EmailVerificationToken(
                userId = userId,
                tenantId = tenantId,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(EMAIL_VERIFICATION_TTL_SECONDS),
            ),
        )

        val verifyUrl = "$baseUrl/t/${tenant.slug}/verify-email?token=$rawToken"
        emailScope.launch {
            try {
                emailPort.sendVerificationEmail(user.email, user.fullName, verifyUrl, tenant.displayName, tenant)
            } catch (e: Exception) {
                log.warn(
                    "Verification email delivery failed tenantId={} userId={}: {}",
                    tenantId.value,
                    userId.value,
                    e.message,
                    e,
                )
            }
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.EMAIL_VERIFICATION_SENT,
                ipAddress = null,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    fun confirmEmailVerification(rawToken: String): SelfServiceResult<Unit> {
        val hash = sha256Hex(rawToken)
        val token =
            evTokenRepo.findByTokenHash(hash)
                ?: return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Verification link is invalid."))

        if (!token.isValid) {
            val msg =
                if (token.isExpired) {
                    "Verification link has expired. Please request a new one."
                } else {
                    "Email address has already been verified."
                }
            return SelfServiceResult.Failure(SelfServiceError.TokenExpired(msg))
        }

        val user =
            userRepository.findById(token.userId, token.tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        userRepository.update(user.copy(emailVerified = true))
        evTokenRepo.markUsed(token.id!!)

        auditLog.record(
            AuditEvent(
                tenantId = token.tenantId,
                userId = token.userId,
                clientId = null,
                eventType = AuditEventType.EMAIL_VERIFIED,
                ipAddress = null,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    fun initiateForgotPassword(
        email: String,
        tenantSlug: String,
        baseUrl: String,
        ipAddress: String?,
    ): SelfServiceResult<Unit> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SelfServiceResult.Success(Unit)

        if (!tenant.isSmtpReady) return SelfServiceResult.Success(Unit)

        val user =
            userRepository.findByEmail(tenant.id, email.trim().lowercase())
                ?: return SelfServiceResult.Success(Unit)

        if (!user.enabled) return SelfServiceResult.Success(Unit)

        val (rawToken, tokenHash) = generateToken()

        prTokenRepo.deleteByUserAndPurpose(user.id!!, TokenPurpose.PASSWORD_RESET)
        prTokenRepo.create(
            PasswordResetToken(
                userId = user.id,
                tenantId = tenant.id,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(PASSWORD_RESET_TTL_SECONDS),
                ipAddress = ipAddress,
            ),
        )

        val resetUrl = "$baseUrl/t/${tenant.slug}/reset-password?token=$rawToken"
        emailScope.launch {
            try {
                emailPort.sendPasswordResetEmail(user.email, user.fullName, resetUrl, tenant.displayName, tenant)
            } catch (e: Exception) {
                log.warn(
                    "Password reset email delivery failed tenantId={} userId={}: {}",
                    tenant.id.value,
                    user.id.value,
                    e.message,
                    e,
                )
            }
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.PASSWORD_RESET_REQUESTED,
                ipAddress = ipAddress,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    fun confirmPasswordReset(
        rawToken: String,
        newPassword: String,
        confirmPassword: String,
    ): SelfServiceResult<Unit> {
        val hash = sha256Hex(rawToken)
        val token =
            prTokenRepo.findByTokenHash(hash)
                ?: return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Reset link is invalid."))

        if (token.purpose != TokenPurpose.PASSWORD_RESET) {
            return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Reset link is invalid."))
        }

        val tenant = tenantRepository.findById(token.tenantId)
        if (tenant != null && !tenant.securityConfig.passwordLoginEnabled) {
            return SelfServiceResult.Failure(SelfServiceError.PasswordLoginDisabled())
        }

        if (!token.isValid) {
            val msg =
                if (token.isExpired) {
                    "Reset link has expired. Please request a new one."
                } else {
                    "This reset link has already been used."
                }
            return SelfServiceResult.Failure(SelfServiceError.TokenExpired(msg))
        }

        if (newPassword.isBlank()) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Password cannot be empty."))
        }
        if (newPassword != confirmPassword) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Passwords do not match."))
        }

        if (tenant != null) {
            validatePasswordPolicy(
                newPassword,
                tenant,
                passwordPolicy,
                token.userId,
                token.tenantId,
                checkHistory = true,
            )?.let { return SelfServiceResult.Failure(it) }
        }

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)
        userRepository.updatePassword(token.userId, hashedPassword, now)
        userRepository.resetFailedLogins(token.userId)
        sessionRepository.revokeAllForUser(token.tenantId, token.userId, now)
        prTokenRepo.markUsed(token.id!!, now)

        if (tenant != null && tenant.isSmtpReady) {
            val resetUser = userRepository.findById(token.userId, token.tenantId)
            if (resetUser != null) {
                emailScope.launch {
                    try {
                        emailPort.sendPasswordChangedEmail(
                            resetUser.email,
                            resetUser.fullName,
                            tenant.displayName,
                            tenant,
                        )
                    } catch (e: Exception) {
                        log.warn(
                            "Password changed email failed tenantId={} userId={}: {}",
                            token.tenantId.value,
                            token.userId.value,
                            e.message,
                        )
                    }
                }
            }
        }

        if (tenant != null && passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            passwordPolicy.recordPasswordHistory(token.userId, token.tenantId, hashedPassword)
        }

        auditLog.record(
            AuditEvent(
                tenantId = token.tenantId,
                userId = token.userId,
                clientId = null,
                eventType = AuditEventType.PASSWORD_RESET_COMPLETED,
                ipAddress = null,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    fun sendAccountLockedNotification(
        user: User,
        tenant: Tenant,
        baseUrl: String,
    ) {
        if (!tenant.isSmtpReady) return

        val (rawToken, tokenHash) = generateToken()
        prTokenRepo.deleteByUserAndPurpose(user.id!!, TokenPurpose.PASSWORD_RESET)
        prTokenRepo.create(
            PasswordResetToken(
                userId = user.id,
                tenantId = tenant.id,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(PASSWORD_RESET_TTL_SECONDS),
                ipAddress = null,
            ),
        )

        val resetUrl = "$baseUrl/t/${tenant.slug}/reset-password?token=$rawToken"
        val duration = formatLockoutDuration(tenant.securityConfig.lockoutDurationMinutes)

        emailScope.launch {
            try {
                emailPort.sendAccountLockedEmail(
                    user.email,
                    user.fullName,
                    resetUrl,
                    tenant.displayName,
                    duration,
                    tenant,
                )
            } catch (e: Exception) {
                log.warn(
                    "Account locked email failed tenantId={} userId={}: {}",
                    tenant.id.value,
                    user.id.value,
                    e.message,
                )
            }
        }
    }

    fun initiateInvite(
        user: User,
        tenant: Tenant,
        baseUrl: String,
    ): SelfServiceResult<Unit> {
        if (!tenant.isSmtpReady) {
            return SelfServiceResult.Failure(
                SelfServiceError.SmtpNotConfigured("Email delivery is not configured for this workspace."),
            )
        }

        val (rawToken, tokenHash) = generateToken()

        prTokenRepo.deleteByUserAndPurpose(user.id!!, TokenPurpose.INVITE)
        prTokenRepo.create(
            PasswordResetToken(
                userId = user.id,
                tenantId = tenant.id,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(INVITE_TTL_SECONDS),
                purpose = TokenPurpose.INVITE,
            ),
        )

        val inviteUrl = "$baseUrl/t/${tenant.slug}/accept-invite?token=$rawToken"
        emailScope.launch {
            try {
                emailPort.sendInviteEmail(
                    to = user.email,
                    toName = user.fullName,
                    inviteUrl = inviteUrl,
                    workspaceName = tenant.displayName,
                    tenant = tenant,
                )
            } catch (e: Exception) {
                log.warn(
                    "Invite email delivery failed tenantId={} userId={}: {}",
                    tenant.id.value,
                    user.id.value,
                    e.message,
                )
            }
        }

        return SelfServiceResult.Success(Unit)
    }

    fun confirmAcceptInvite(
        rawToken: String,
        newPassword: String,
        confirmPassword: String,
    ): SelfServiceResult<User> {
        val hash = sha256Hex(rawToken)
        val token =
            prTokenRepo.findByTokenHash(hash)
                ?: return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Invite link is invalid."))

        if (token.purpose != TokenPurpose.INVITE) {
            return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Invite link is invalid."))
        }

        val tenant = tenantRepository.findById(token.tenantId)
        if (tenant != null && !tenant.securityConfig.passwordLoginEnabled) {
            return SelfServiceResult.Failure(SelfServiceError.PasswordLoginDisabled())
        }

        if (!token.isValid) {
            val msg =
                if (token.isExpired) {
                    "This invite link has expired. Please contact your administrator for a new invite."
                } else {
                    "This invite link has already been used."
                }
            return SelfServiceResult.Failure(SelfServiceError.TokenExpired(msg))
        }

        if (newPassword.isBlank()) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Password cannot be empty."))
        }
        if (newPassword != confirmPassword) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Passwords do not match."))
        }

        if (tenant != null) {
            validatePasswordPolicy(newPassword, tenant, passwordPolicy)
                ?.let { return SelfServiceResult.Failure(it) }
        }

        userRepository.findById(token.userId, token.tenantId)
            ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)

        userRepository.updatePassword(token.userId, hashedPassword, now)

        val freshUser =
            userRepository.findById(token.userId, token.tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))
        userRepository.update(
            freshUser.copy(
                emailVerified = true,
                requiredActions = freshUser.requiredActions - RequiredAction.SET_PASSWORD,
            ),
        )

        prTokenRepo.markUsed(token.id!!, now)

        if (tenant != null && passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            passwordPolicy.recordPasswordHistory(token.userId, token.tenantId, hashedPassword)
        }

        auditLog.record(
            AuditEvent(
                tenantId = token.tenantId,
                userId = token.userId,
                clientId = null,
                eventType = AuditEventType.USER_INVITE_ACCEPTED,
                ipAddress = null,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(
            userRepository.findById(token.userId, token.tenantId)!!,
        )
    }

    fun initiateForcedPasswordChange(user: User): SelfServiceResult<String> {
        val userId = user.id ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))
        val (rawToken, tokenHash) = generateToken()

        prTokenRepo.deleteByUserAndPurpose(userId, TokenPurpose.TEMP_PASSWORD)
        prTokenRepo.create(
            PasswordResetToken(
                userId = userId,
                tenantId = user.tenantId,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(TEMP_PASSWORD_TTL_SECONDS),
                purpose = TokenPurpose.TEMP_PASSWORD,
            ),
        )

        val fresh =
            userRepository.findById(userId, user.tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))
        userRepository.update(
            fresh.copy(requiredActions = fresh.requiredActions + RequiredAction.CHANGE_PASSWORD),
        )
        return SelfServiceResult.Success(rawToken)
    }

    fun confirmForcedPasswordChange(
        rawToken: String,
        newPassword: String,
        confirmPassword: String,
    ): SelfServiceResult<Unit> {
        val hash = sha256Hex(rawToken)
        val token =
            prTokenRepo.findByTokenHash(hash)
                ?: return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Change-password link is invalid."))

        if (token.purpose != TokenPurpose.TEMP_PASSWORD) {
            return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Change-password link is invalid."))
        }

        val tenant = tenantRepository.findById(token.tenantId)
        if (tenant != null && !tenant.securityConfig.passwordLoginEnabled) {
            return SelfServiceResult.Failure(SelfServiceError.PasswordLoginDisabled())
        }

        if (!token.isValid) {
            val msg =
                if (token.isExpired) {
                    "Change-password link has expired. Ask your administrator to issue a new one."
                } else {
                    "This change-password link has already been used."
                }
            return SelfServiceResult.Failure(SelfServiceError.TokenExpired(msg))
        }

        if (newPassword.isBlank()) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Password cannot be empty."))
        }
        if (newPassword != confirmPassword) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Passwords do not match."))
        }

        if (tenant != null) {
            validatePasswordPolicy(
                newPassword,
                tenant,
                passwordPolicy,
                token.userId,
                token.tenantId,
                checkHistory = true,
            )?.let { return SelfServiceResult.Failure(it) }
        }

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)
        userRepository.updatePassword(token.userId, hashedPassword, now)
        userRepository.resetFailedLogins(token.userId)
        sessionRepository.revokeAllForUser(token.tenantId, token.userId, now)
        prTokenRepo.markUsed(token.id!!, now)

        val freshUser =
            userRepository.findById(token.userId, token.tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))
        userRepository.update(
            freshUser.copy(requiredActions = freshUser.requiredActions - RequiredAction.CHANGE_PASSWORD),
        )

        if (tenant != null && passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            passwordPolicy.recordPasswordHistory(token.userId, token.tenantId, hashedPassword)
        }

        auditLog.record(
            AuditEvent(
                tenantId = token.tenantId,
                userId = token.userId,
                clientId = null,
                eventType = AuditEventType.PASSWORD_RESET_COMPLETED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("method" to "forced_change"),
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    fun initiateMagicLink(
        email: String,
        tenantSlug: String,
        baseUrl: String,
        ipAddress: String?,
    ): SelfServiceResult<Unit> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SelfServiceResult.Success(Unit)

        if (!tenant.securityConfig.magicLinkEnabled) return SelfServiceResult.Success(Unit)
        if (!tenant.isSmtpReady) return SelfServiceResult.Success(Unit)

        val user =
            userRepository.findByEmail(tenant.id, email.trim().lowercase())
                ?: return SelfServiceResult.Success(Unit)

        if (!user.enabled) return SelfServiceResult.Success(Unit)

        val (rawToken, tokenHash) = generateToken()

        val ttlMinutes = tenant.securityConfig.magicLinkTokenTtlMinutes.coerceIn(1, 1440)
        prTokenRepo.deleteByUserAndPurpose(user.id!!, TokenPurpose.MAGIC_LINK)
        prTokenRepo.create(
            PasswordResetToken(
                userId = user.id,
                tenantId = tenant.id,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(ttlMinutes * 60L),
                purpose = TokenPurpose.MAGIC_LINK,
                ipAddress = ipAddress,
            ),
        )

        val magicLinkUrl = "$baseUrl/t/${tenant.slug}/magic-link/consume?token=$rawToken"
        emailScope.launch {
            try {
                emailPort.sendMagicLinkEmail(user.email, user.fullName, magicLinkUrl, tenant.displayName, tenant)
            } catch (e: Exception) {
                log.warn(
                    "Magic link email delivery failed tenantId={} userId={}: {}",
                    tenant.id.value,
                    user.id.value,
                    e.message,
                    e,
                )
            }
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.MAGIC_LINK_REQUESTED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("ttl_minutes" to ttlMinutes.toString()),
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    /**
     * Consumes a magic-link token presented at [tenantId] — the tenant the consume URL names.
     *
     * The token hash is global, so a link minted at one tenant resolves at another's URL. The
     * tenant guard runs before the token is marked used, so a consume aimed at the wrong tenant
     * leaves the token alive for its owner; the same refusal message as an unknown token keeps it
     * from telling the caller which tenant the token belongs to.
     */
    fun consumeMagicLink(
        rawToken: String,
        tenantId: TenantId,
    ): SelfServiceResult<User> {
        val hash = sha256Hex(rawToken)
        val token =
            prTokenRepo.findByTokenHash(hash)
                ?: return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Sign-in link is invalid."))

        if (token.purpose != TokenPurpose.MAGIC_LINK || token.tenantId != tenantId) {
            return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Sign-in link is invalid."))
        }

        if (!token.isValid) {
            val msg =
                if (token.isExpired) {
                    "Sign-in link has expired. Request a new one."
                } else {
                    "This sign-in link has already been used."
                }
            return SelfServiceResult.Failure(SelfServiceError.TokenExpired(msg))
        }

        val user =
            userRepository.findById(token.userId, token.tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        if (!user.enabled) {
            return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Sign-in link is invalid."))
        }

        if (RequiredAction.CHANGE_PASSWORD in user.requiredActions) {
            return SelfServiceResult.Failure(
                SelfServiceError.Unauthorized("A password change is required before you can sign in."),
            )
        }

        userRepository.update(
            user.copy(
                emailVerified = true,
                requiredActions = user.requiredActions - RequiredAction.SET_PASSWORD,
            ),
        )

        prTokenRepo.markUsed(token.id!!, Instant.now())

        auditLog.record(
            AuditEvent(
                tenantId = token.tenantId,
                userId = token.userId,
                clientId = null,
                eventType = AuditEventType.MAGIC_LINK_CONSUMED,
                ipAddress = null,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(userRepository.findById(token.userId, token.tenantId)!!)
    }

    private fun generateToken(): Pair<String, String> {
        val token = SecureTokens.randomBase64Url(32)
        return token to sha256Hex(token)
    }

    private fun formatLockoutDuration(minutes: Int): String =
        when {
            minutes < 60 -> "$minutes minute${if (minutes == 1) "" else "s"}"
            minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes / 60 == 1) "" else "s"}"
            else -> "$minutes minutes"
        }
}
