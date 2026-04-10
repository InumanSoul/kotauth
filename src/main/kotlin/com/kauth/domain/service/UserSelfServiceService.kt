package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.EmailVerificationToken
import com.kauth.domain.model.PasswordResetToken
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Domain service — user self-service use cases.
 *
 * Handles everything a logged-in user can do on their own account:
 *   - Email verification (initiate + confirm)
 *   - Forgot password (initiate + confirm)
 *   - Profile update (email, full name)
 *   - Password change (with current-password verification)
 *   - Session listing and self-revocation
 *
 * Security invariants:
 *   - Forgot-password flow always returns success — never reveals if an email exists.
 *   - Password change + reset always revoke all existing sessions.
 *   - Token lookups compare against SHA-256 hashes — raw tokens never touch this layer.
 *   - Session ownership is verified before revocation (a user cannot revoke another's session).
 *
 * Returns [SelfServiceResult] — same discriminated-union pattern as [AdminResult].
 * No exceptions cross layer boundaries.
 */
class UserSelfServiceService(
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
    private val log = LoggerFactory.getLogger(UserSelfServiceService::class.java)

    // =========================================================================
    // Email verification
    // =========================================================================

    /**
     * Generates a verification token and sends a verification email.
     * Replaces any previously unused token for this user.
     * Returns [SelfServiceError.SmtpNotConfigured] if the tenant has no SMTP.
     */
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
            return SelfServiceResult.Success(Unit) // already verified — no-op
        }

        val (rawToken, tokenHash) = generateToken()

        evTokenRepo.deleteUnusedByUser(userId)
        evTokenRepo.create(
            EmailVerificationToken(
                userId = userId,
                tenantId = tenantId,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(86400), // 24 hours
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

    /**
     * Confirms an email address using a raw token from the verification link.
     * Marks the token as used and flips [User.emailVerified].
     */
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

    // =========================================================================
    // Forgot password
    // =========================================================================

    /**
     * Initiates a password reset for the email address if it exists in the tenant.
     *
     * SECURITY: always returns [SelfServiceResult.Success] — this prevents
     * user enumeration even when the email is not found. The caller should
     * display a generic "if an account exists, you'll receive an email" message.
     */
    fun initiateForgotPassword(
        email: String,
        tenantSlug: String,
        baseUrl: String,
        ipAddress: String?,
    ): SelfServiceResult<Unit> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return SelfServiceResult.Success(Unit) // fail silently — tenant slug visible in URL

        if (!tenant.isSmtpReady) return SelfServiceResult.Success(Unit) // silent — don't leak config state

        val user =
            userRepository.findByEmail(tenant.id, email.trim().lowercase())
                ?: return SelfServiceResult.Success(Unit) // user doesn't exist — don't reveal

        if (!user.enabled) return SelfServiceResult.Success(Unit) // disabled account — silent

        val (rawToken, tokenHash) = generateToken()

        prTokenRepo.deleteByUserAndPurpose(user.id!!, TokenPurpose.PASSWORD_RESET)
        prTokenRepo.create(
            PasswordResetToken(
                userId = user.id,
                tenantId = tenant.id,
                tokenHash = tokenHash,
                expiresAt = Instant.now().plusSeconds(3600), // 1 hour
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

    /**
     * Completes a password reset using a raw token from the reset link.
     * On success: password updated, all sessions revoked, token marked used.
     */
    fun confirmPasswordReset(
        rawToken: String,
        newPassword: String,
        confirmPassword: String,
    ): SelfServiceResult<Unit> {
        val hash = sha256Hex(rawToken)
        val token =
            prTokenRepo.findByTokenHash(hash)
                ?: return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Reset link is invalid."))

        // Purpose guard — invite tokens must not be usable on the reset endpoint
        if (token.purpose != TokenPurpose.PASSWORD_RESET) {
            return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Reset link is invalid."))
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

        val tenant = tenantRepository.findById(token.tenantId)
        if (tenant != null) {
            validatePasswordPolicy(newPassword, tenant, token.userId, token.tenantId, checkHistory = true)
                ?.let { return SelfServiceResult.Failure(it) }
        }

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)
        userRepository.updatePassword(token.userId, hashedPassword, now)
        userRepository.resetFailedLogins(token.userId) // clear lockout on password reset
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

        // Record in password history
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

    // =========================================================================
    // Self-service profile management
    // =========================================================================

    fun getProfile(
        userId: UserId,
        tenantId: TenantId,
    ): SelfServiceResult<User> {
        val user =
            userRepository.findById(userId, tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))
        return SelfServiceResult.Success(user)
    }

    fun updateProfile(
        userId: UserId,
        tenantId: TenantId,
        email: String,
        fullName: String,
    ): SelfServiceResult<User> {
        val user =
            userRepository.findById(userId, tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        if (email.isBlank() || !email.contains('@')) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("A valid email address is required."))
        }
        if (fullName.isBlank()) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Full name is required."))
        }

        val newEmail = email.trim().lowercase()
        if (newEmail != user.email && userRepository.existsByEmail(tenantId, newEmail)) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("That email address is already in use."))
        }

        // If email changed, require re-verification
        val emailChanged = newEmail != user.email
        val updated =
            userRepository.update(
                user.copy(
                    email = newEmail,
                    fullName = fullName.trim(),
                    emailVerified = if (emailChanged) false else user.emailVerified,
                ),
            )

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.USER_PROFILE_UPDATED,
                ipAddress = null,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(updated)
    }

    /**
     * Changes the user's own password.
     * Requires current password verification. Revokes all active sessions on success.
     */
    fun changePassword(
        userId: UserId,
        tenantId: TenantId,
        currentPassword: String,
        newPassword: String,
        confirmPassword: String,
    ): SelfServiceResult<Unit> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("Workspace not found."))
        val user =
            userRepository.findById(userId, tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        if (!passwordHasher.verify(currentPassword, user.passwordHash)) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Current password is incorrect."))
        }
        if (newPassword.isBlank()) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("New password cannot be empty."))
        }
        if (newPassword != confirmPassword) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Passwords do not match."))
        }

        // Enforce password policy + history
        validatePasswordPolicy(newPassword, tenant, userId, tenantId, checkHistory = true)
            ?.let { return SelfServiceResult.Failure(it) }

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)
        userRepository.updatePassword(userId, hashedPassword, now)
        sessionRepository.revokeAllForUser(tenantId, userId, now)

        if (tenant.isSmtpReady) {
            emailScope.launch {
                try {
                    emailPort.sendPasswordChangedEmail(user.email, user.fullName, tenant.displayName, tenant)
                } catch (e: Exception) {
                    log.warn(
                        "Password changed email failed tenantId={} userId={}: {}",
                        tenantId.value,
                        userId.value,
                        e.message,
                    )
                }
            }
        }

        // Record in password history
        if (passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            passwordPolicy.recordPasswordHistory(userId, tenantId, hashedPassword)
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.USER_PASSWORD_CHANGED,
                ipAddress = null,
                userAgent = null,
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    // =========================================================================
    // Security notifications
    // =========================================================================

    /**
     * Sends an account-locked notification email with an embedded password reset link.
     * Generates a fresh reset token so the user can bypass the lockout window immediately.
     * Silent no-op if the tenant has no SMTP configured.
     */
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
                expiresAt = Instant.now().plusSeconds(3600),
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

    private fun formatLockoutDuration(minutes: Int): String =
        when {
            minutes < 60 -> "$minutes minute${if (minutes == 1) "" else "s"}"
            minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes / 60 == 1) "" else "s"}"
            else -> "$minutes minutes"
        }

    // =========================================================================
    // Session management
    // =========================================================================

    /** Returns all active sessions for the given user. */
    fun getActiveSessions(
        userId: UserId,
        tenantId: TenantId,
    ): List<Session> = sessionRepository.findActiveByUser(tenantId, userId)

    /**
     * Revokes a single session. Verifies ownership — a user cannot revoke
     * another user's session through this path.
     */
    fun revokeSession(
        userId: UserId,
        tenantId: TenantId,
        sessionId: SessionId,
    ): SelfServiceResult<Unit> {
        val session =
            sessionRepository.findById(sessionId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("Session not found."))

        if (session.userId != userId || session.tenantId != tenantId) {
            return SelfServiceResult.Failure(SelfServiceError.Unauthorized("Cannot revoke this session."))
        }

        sessionRepository.revoke(sessionId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.USER_SESSION_REVOKED_SELF,
                ipAddress = null,
                userAgent = null,
                details = mapOf("sessionId" to sessionId.value.toString()),
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    fun revokeOtherSessions(
        userId: UserId,
        tenantId: TenantId,
        keepSessionId: SessionId,
    ): SelfServiceResult<Int> {
        val active = sessionRepository.findActiveByUser(tenantId, userId)
        var revoked = 0
        for (s in active) {
            if (s.id != null && s.id != keepSessionId) {
                sessionRepository.revoke(s.id)
                revoked++
            }
        }

        if (revoked > 0) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenantId,
                    userId = userId,
                    clientId = null,
                    eventType = AuditEventType.USER_SESSION_REVOKED_SELF,
                    ipAddress = null,
                    userAgent = null,
                    details = mapOf("action" to "revoke_others", "count" to revoked.toString()),
                ),
            )
        }

        return SelfServiceResult.Success(revoked)
    }

    fun disableAccount(
        userId: UserId,
        tenantId: TenantId,
    ): SelfServiceResult<Unit> {
        val user =
            userRepository.findById(userId, tenantId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        userRepository.update(user.copy(enabled = false))
        sessionRepository.revokeAllForUser(tenantId, userId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.USER_ACCOUNT_DISABLED_SELF,
                ipAddress = null,
                userAgent = null,
                details = emptyMap(),
            ),
        )

        return SelfServiceResult.Success(Unit)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Generates a cryptographically secure 32-byte token.
     * Returns a pair of (rawToken, sha256Hash).
     * Raw token is base64url-encoded (43 chars, URL-safe).
     */
    private fun generateToken(): Pair<String, String> {
        val token = SecureTokens.randomBase64Url(32)
        return token to sha256Hex(token)
    }

    /**
     * Validates a new password against the tenant's policy and optionally against password history.
     * Returns a [SelfServiceError.Validation] if the password fails, or null if it passes.
     */
    private fun validatePasswordPolicy(
        newPassword: String,
        tenant: Tenant,
        userId: UserId? = null,
        tenantId: TenantId? = null,
        checkHistory: Boolean = false,
    ): SelfServiceError.Validation? {
        val policyError = passwordPolicy?.validate(newPassword, tenant)
        if (policyError != null) return SelfServiceError.Validation(policyError)
        if (passwordPolicy == null && newPassword.length < tenant.passwordPolicyMinLength) {
            return SelfServiceError.Validation(
                "Password must be at least ${tenant.passwordPolicyMinLength} characters.",
            )
        }
        if (checkHistory &&
            passwordPolicy != null &&
            tenant.passwordPolicyHistoryCount > 0 &&
            userId != null &&
            tenantId != null
        ) {
            if (passwordPolicy.isInHistory(userId, tenantId, newPassword, tenant.passwordPolicyHistoryCount)) {
                return SelfServiceError.Validation(
                    "This password has been used recently. Please choose a different password.",
                )
            }
        }
        return null
    }

    // =========================================================================
    // Invite flow
    // =========================================================================

    /**
     * Creates an invite token and sends the invite email.
     * Called by [AdminService.createUser] when sendInvite = true, and by resend-invite.
     */
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
                expiresAt = Instant.now().plusSeconds(72 * 3600), // 72 hours
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

    /**
     * Processes the accept-invite form submission.
     * On success: password set, email verified, SET_PASSWORD cleared, token consumed.
     * Does NOT create a session — user goes through normal login.
     */
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

        val tenant = tenantRepository.findById(token.tenantId)
        if (tenant != null) {
            validatePasswordPolicy(newPassword, tenant)
                ?.let { return SelfServiceResult.Failure(it) }
        }

        userRepository.findById(token.userId, token.tenantId)
            ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)

        userRepository.updatePassword(token.userId, hashedPassword, now)

        // Re-fetch after updatePassword to avoid stale snapshot
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

        // Record first password in history
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
}

// =============================================================================
// Result types
// =============================================================================

sealed class SelfServiceResult<out T> {
    data class Success<T>(
        val value: T,
    ) : SelfServiceResult<T>()

    data class Failure(
        val error: SelfServiceError,
    ) : SelfServiceResult<Nothing>()
}

sealed class SelfServiceError(
    val message: String,
) {
    class NotFound(
        message: String,
    ) : SelfServiceError(message)

    class Validation(
        message: String,
    ) : SelfServiceError(message)

    class Unauthorized(
        message: String,
    ) : SelfServiceError(message)

    class TokenExpired(
        message: String,
    ) : SelfServiceError(message)

    class TokenInvalid(
        message: String,
    ) : SelfServiceError(message)

    class SmtpNotConfigured(
        message: String,
    ) : SelfServiceError(message)
}
