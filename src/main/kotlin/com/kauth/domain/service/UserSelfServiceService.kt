package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.EmailPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

class UserSelfServiceService(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val sessionRepository: SessionRepository,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val emailPort: EmailPort,
    private val passwordPolicy: PasswordPolicyPort? = null,
    private val emailScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val log = LoggerFactory.getLogger(UserSelfServiceService::class.java)

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

        if (!tenant.securityConfig.passwordLoginEnabled) {
            return SelfServiceResult.Failure(SelfServiceError.PasswordLoginDisabled())
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

    fun getActiveSessions(
        userId: UserId,
        tenantId: TenantId,
    ): List<Session> = sessionRepository.findActiveByUser(tenantId, userId)

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
}

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

    class PasswordLoginDisabled(
        message: String = "Password sign-in is disabled for this workspace.",
    ) : SelfServiceError(message)
}
