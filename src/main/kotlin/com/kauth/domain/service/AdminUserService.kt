package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.RequiredAction
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

class AdminUserService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val credentialFlowService: CredentialFlowService,
    private val passwordPolicy: PasswordPolicyPort? = null,
    private val emailPort: EmailPort? = null,
) {
    fun createUser(
        tenantId: TenantId,
        username: String,
        email: String,
        fullName: String,
        password: String? = null,
        sendInvite: Boolean = false,
        baseUrl: String = "",
        externalId: String? = null,
        givenName: String? = null,
        familyName: String? = null,
        // False lets a caller that holds this create inside its own DB transaction (SCIM's
        // create-then-enable/disable pair) defer the SMTP round-trip until after it commits,
        // via dispatchPendingInvite. Every other caller keeps the send-inline default.
        dispatchInvite: Boolean = true,
    ): AdminResult<User> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace not found."))

        if (username.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Username is required."))
        }
        if (!username.matches(Regex("[a-zA-Z0-9._@+-]+"))) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "Username may only contain letters, digits, dots, underscores, hyphens, @, and +.",
                ),
            )
        }
        if (email.isBlank() || !email.contains('@')) {
            return AdminResult.Failure(AdminError.Validation("A valid email address is required."))
        }

        val resolvedPasswordHash: String
        val resolvedRequiredActions: Set<RequiredAction>

        if (sendInvite) {
            resolvedPasswordHash = User.SENTINEL_PASSWORD_HASH
            resolvedRequiredActions = setOf(RequiredAction.SET_PASSWORD)
        } else {
            if (!tenant.securityConfig.passwordLoginEnabled) {
                return AdminResult.Failure(
                    AdminError.Validation(
                        "Password sign-in is disabled for this workspace. Send an invite instead.",
                    ),
                )
            }
            val pw =
                password
                    ?: return AdminResult.Failure(AdminError.Validation("Password is required."))
            val policyError = passwordPolicy?.validate(pw, tenant)
            if (policyError != null) {
                return AdminResult.Failure(AdminError.Validation(policyError))
            } else if (passwordPolicy == null && pw.length < tenant.passwordPolicyMinLength) {
                return AdminResult.Failure(
                    AdminError.Validation(
                        "Password must be at least ${tenant.passwordPolicyMinLength} characters.",
                    ),
                )
            }
            resolvedPasswordHash = passwordHasher.hash(pw)
            resolvedRequiredActions = emptySet()
        }

        if (userRepository.existsByUsername(tenantId, username)) {
            return AdminResult.Failure(AdminError.Conflict("Username '$username' is already taken."))
        }
        if (userRepository.existsByEmail(tenantId, email)) {
            return AdminResult.Failure(AdminError.Conflict("Email '${email.lowercase()}' is already registered."))
        }

        val user =
            userRepository.save(
                User(
                    tenantId = tenantId,
                    username = username.trim(),
                    email = email.trim().lowercase(),
                    fullName = fullName.trim(),
                    passwordHash = resolvedPasswordHash,
                    emailVerified = !sendInvite,
                    enabled = true,
                    requiredActions = resolvedRequiredActions,
                    externalId = externalId,
                    givenName = givenName,
                    familyName = familyName,
                ),
            )

        if (!sendInvite && passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            passwordPolicy.recordPasswordHistory(user.id!!, tenantId, resolvedPasswordHash)
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.ADMIN_USER_CREATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("username" to username, "invite" to sendInvite.toString()),
            ),
        )

        if (sendInvite && dispatchInvite && tenant.isSmtpReady) {
            dispatchInviteEmail(user, tenant, baseUrl)
        }

        return AdminResult.Success(user)
    }

    /**
     * Sends the invite email [createUser] would otherwise send inline, for a user created with
     * `dispatchInvite = false`. Exists so a caller holding a DB transaction across create — e.g.
     * SCIM provisioning, which must also roll back an enable/disable failure — can commit first
     * and only then pay for the SMTP round-trip, instead of holding a pool connection across it.
     * A no-op (not a failure) when there's no pending invite or SMTP isn't configured, mirroring
     * [createUser]'s own silent skip in those cases.
     */
    fun dispatchPendingInvite(
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

        if (RequiredAction.SET_PASSWORD in user.requiredActions && tenant.isSmtpReady) {
            dispatchInviteEmail(user, tenant, baseUrl)
        }
        return AdminResult.Success(Unit)
    }

    private fun dispatchInviteEmail(
        user: User,
        tenant: Tenant,
        baseUrl: String,
    ) {
        when (credentialFlowService.initiateInvite(user, tenant, baseUrl)) {
            is SelfServiceResult.Success ->
                auditLog.record(
                    AuditEvent(
                        tenantId = tenant.id,
                        userId = user.id,
                        clientId = null,
                        eventType = AuditEventType.USER_INVITE_SENT,
                        ipAddress = null,
                        userAgent = null,
                        details = mapOf("username" to user.username),
                    ),
                )
            is SelfServiceResult.Failure -> Unit
        }
    }

    fun resendInvite(
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

        if (RequiredAction.SET_PASSWORD !in user.requiredActions) {
            return AdminResult.Failure(AdminError.Validation("This user does not have a pending invite."))
        }
        if (!tenant.isSmtpReady) {
            return AdminResult.Failure(AdminError.Validation("SMTP is not configured."))
        }

        return when (val result = credentialFlowService.initiateInvite(user, tenant, baseUrl)) {
            is SelfServiceResult.Success -> {
                auditLog.record(
                    AuditEvent(
                        tenantId = tenantId,
                        userId = userId,
                        clientId = null,
                        eventType = AuditEventType.USER_INVITE_SENT,
                        ipAddress = null,
                        userAgent = null,
                        details = mapOf("username" to user.username, "action" to "resend"),
                    ),
                )
                AdminResult.Success(Unit)
            }
            is SelfServiceResult.Failure ->
                AdminResult.Failure(AdminError.Validation(result.error.message))
        }
    }

    fun getUser(
        userId: UserId,
        tenantId: TenantId,
    ): AdminResult<User> =
        userRepository
            .findById(userId, tenantId)
            ?.let { AdminResult.Success(it) }
            ?: AdminResult.Failure(AdminError.NotFound("User ${userId.value} not found."))

    fun listUsers(
        tenantId: TenantId,
        search: String? = null,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
    ): List<User> = userRepository.findByTenantId(tenantId, search, limit, offset)

    /** Tenant-scoped username lookup — the indexed fast path for SCIM's `userName eq` filter. */
    fun findByUsername(
        tenantId: TenantId,
        username: String,
    ): User? = userRepository.findByUsername(tenantId, username)

    /** Tenant-scoped external-id lookup — the indexed fast path for SCIM's `externalId eq` filter. */
    fun findByExternalId(
        tenantId: TenantId,
        externalId: String,
    ): User? = userRepository.findByExternalId(tenantId, externalId)

    fun countUsers(
        tenantId: TenantId,
        search: String? = null,
    ): Long = userRepository.countByTenantId(tenantId, search)

    fun toggleUserEnabled(
        userId: UserId,
        tenantId: TenantId,
    ): AdminResult<Unit> {
        val user =
            userRepository.findById(userId, tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("User ${userId.value} not found."))
        return setUserEnabled(userId, tenantId, !user.enabled)
    }

    fun sendTestEmail(
        tenantId: TenantId,
        recipientEmail: String,
    ): AdminResult<Unit> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace not found."))
        if (!tenant.isSmtpReady) {
            return AdminResult.Failure(AdminError.Validation("SMTP is not configured for this workspace."))
        }
        val port =
            emailPort
                ?: return AdminResult.Failure(AdminError.Validation("Email delivery is not available."))
        return try {
            port.sendTestEmail(
                to = recipientEmail,
                workspaceName = tenant.displayName,
                tenant = tenant,
            )
            auditLog.record(
                AuditEvent(
                    tenantId = tenantId,
                    userId = null,
                    clientId = null,
                    eventType = AuditEventType.ADMIN_SMTP_TEST,
                    ipAddress = null,
                    userAgent = null,
                    details = mapOf("recipient" to recipientEmail),
                ),
            )
            AdminResult.Success(Unit)
        } catch (e: Exception) {
            AdminResult.Failure(AdminError.Validation("Failed to send test email: ${e.message}"))
        }
    }

    fun revokeAllSessions(tenantId: TenantId): AdminResult<Int> {
        val count = sessionRepository.revokeAllForTenant(tenantId)
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_SESSIONS_REVOKED_ALL,
                ipAddress = null,
                userAgent = null,
                details = mapOf("sessionsRevoked" to count.toString()),
            ),
        )
        return AdminResult.Success(count)
    }

    fun updateUser(
        userId: UserId,
        tenantId: TenantId,
        email: String? = null,
        fullName: String? = null,
    ): AdminResult<User> {
        val user =
            userRepository.findById(userId, tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("User ${userId.value} not found."))

        val resolvedEmail = email?.trim()?.lowercase() ?: user.email
        val resolvedFullName = fullName?.trim() ?: user.fullName

        if (resolvedEmail.isBlank() || !resolvedEmail.contains('@')) {
            return AdminResult.Failure(AdminError.Validation("A valid email address is required."))
        }

        if (resolvedEmail != user.email && userRepository.existsByEmail(tenantId, resolvedEmail)) {
            return AdminResult.Failure(AdminError.Conflict("Email '$resolvedEmail' is already registered."))
        }

        val updated = userRepository.update(user.copy(email = resolvedEmail, fullName = resolvedFullName))

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.ADMIN_USER_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("username" to user.username),
            ),
        )

        return AdminResult.Success(updated)
    }

    /**
     * Replaces the full mutable profile in one write. Unlike [updateUser], every parameter here
     * is authoritative — a null clears the field. Used by the SCIM PUT/PATCH flow and by the admin
     * profile form, both of which supply a fully resolved desired state rather than a partial edit.
     */
    fun replaceUserProfile(
        userId: UserId,
        tenantId: TenantId,
        email: String,
        fullName: String,
        externalId: String?,
        givenName: String?,
        familyName: String?,
    ): AdminResult<User> {
        val user =
            userRepository.findById(userId, tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("User ${userId.value} not found."))

        val resolvedEmail = email.trim().lowercase()
        if (resolvedEmail.isBlank() || !resolvedEmail.contains('@')) {
            return AdminResult.Failure(AdminError.Validation("A valid email address is required."))
        }
        if (resolvedEmail != user.email && userRepository.existsByEmail(tenantId, resolvedEmail)) {
            return AdminResult.Failure(AdminError.Conflict("Email '$resolvedEmail' is already registered."))
        }

        val updated =
            userRepository.update(
                user.copy(
                    email = resolvedEmail,
                    fullName = fullName.trim(),
                    externalId = externalId,
                    givenName = givenName,
                    familyName = familyName,
                ),
            )

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.ADMIN_USER_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("username" to user.username),
            ),
        )

        return AdminResult.Success(updated)
    }

    fun setUserEnabled(
        userId: UserId,
        tenantId: TenantId,
        enabled: Boolean,
    ): AdminResult<Unit> {
        val user =
            userRepository.findById(userId, tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("User ${userId.value} not found."))

        userRepository.update(user.copy(enabled = enabled))

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = if (enabled) AuditEventType.ADMIN_USER_ENABLED else AuditEventType.ADMIN_USER_DISABLED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("username" to user.username, "enabled" to enabled.toString()),
            ),
        )

        return AdminResult.Success(Unit)
    }
}
