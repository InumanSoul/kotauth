package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.EmailVerificationToken
import com.kauth.domain.model.PasswordResetToken
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
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
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

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
            userRepository.findById(userId)
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
            return SelfServiceResult.Failure(
                SelfServiceError.EmailDeliveryFailed(
                    "Failed to send verification email. Please try again later.",
                ),
            )
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
        val hash = sha256(rawToken)
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
            userRepository.findById(token.userId)
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

        prTokenRepo.deleteByUser(user.id!!)
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
        try {
            emailPort.sendPasswordResetEmail(user.email, user.fullName, resetUrl, tenant.displayName, tenant)
        } catch (e: Exception) {
            // Log but do NOT surface to caller — attacker must not learn the email exists
            log.warn(
                "Password reset email delivery failed tenantId={} userId={}: {}",
                tenant.id.value,
                user.id.value,
                e.message,
                e,
            )
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
        val hash = sha256(rawToken)
        val token =
            prTokenRepo.findByTokenHash(hash)
                ?: return SelfServiceResult.Failure(SelfServiceError.TokenInvalid("Reset link is invalid."))

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
            val policyError = passwordPolicy?.validate(newPassword, tenant)
            if (policyError != null) {
                return SelfServiceResult.Failure(SelfServiceError.Validation(policyError))
            } else if (passwordPolicy == null && newPassword.length < tenant.passwordPolicyMinLength) {
                return SelfServiceResult.Failure(
                    SelfServiceError.Validation(
                        "Password must be at least ${tenant.passwordPolicyMinLength} characters.",
                    ),
                )
            }
            // Check password history
            if (passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
                if (passwordPolicy.isInHistory(
                        token.userId,
                        token.tenantId,
                        newPassword,
                        tenant.passwordPolicyHistoryCount,
                    )
                ) {
                    return SelfServiceResult.Failure(
                        SelfServiceError.Validation(
                            "This password has been used recently. Please choose a different password.",
                        ),
                    )
                }
            }
        }

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)
        userRepository.updatePassword(token.userId, hashedPassword, now)
        sessionRepository.revokeAllForUser(token.tenantId, token.userId, now)
        prTokenRepo.markUsed(token.id!!, now)

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

    /**
     * Updates the user's own profile (email, full name).
     * Email uniqueness is re-checked if it changed.
     */
    fun updateProfile(
        userId: UserId,
        tenantId: TenantId,
        email: String,
        fullName: String,
    ): SelfServiceResult<User> {
        val user =
            userRepository.findById(userId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        if (user.tenantId != tenantId) {
            return SelfServiceResult.Failure(SelfServiceError.Unauthorized("Access denied."))
        }
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
            userRepository.findById(userId)
                ?: return SelfServiceResult.Failure(SelfServiceError.NotFound("User not found."))

        if (user.tenantId != tenantId) {
            return SelfServiceResult.Failure(SelfServiceError.Unauthorized("Access denied."))
        }
        if (!passwordHasher.verify(currentPassword, user.passwordHash)) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Current password is incorrect."))
        }
        if (newPassword.isBlank()) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("New password cannot be empty."))
        }
        if (newPassword != confirmPassword) {
            return SelfServiceResult.Failure(SelfServiceError.Validation("Passwords do not match."))
        }

        // Enforce full password policy
        val policyError = passwordPolicy?.validate(newPassword, tenant)
        if (policyError != null) {
            return SelfServiceResult.Failure(SelfServiceError.Validation(policyError))
        } else if (passwordPolicy == null && newPassword.length < tenant.passwordPolicyMinLength) {
            return SelfServiceResult.Failure(
                SelfServiceError.Validation(
                    "Password must be at least ${tenant.passwordPolicyMinLength} characters.",
                ),
            )
        }
        // Check password history
        if (passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            if (passwordPolicy.isInHistory(userId, tenantId, newPassword, tenant.passwordPolicyHistoryCount)) {
                return SelfServiceResult.Failure(
                    SelfServiceError.Validation(
                        "This password has been used recently. Please choose a different password.",
                    ),
                )
            }
        }

        val now = Instant.now()
        val hashedPassword = passwordHasher.hash(newPassword)
        userRepository.updatePassword(userId, hashedPassword, now)
        sessionRepository.revokeAllForUser(tenantId, userId, now)

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

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Generates a cryptographically secure 32-byte token.
     * Returns a pair of (rawToken, sha256Hash).
     * Raw token is base64url-encoded (43 chars, URL-safe).
     */
    private fun generateToken(): Pair<String, String> {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return token to sha256(token)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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

    class EmailDeliveryFailed(
        message: String,
    ) : SelfServiceError(message)
}
