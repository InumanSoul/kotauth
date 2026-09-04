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
    private val collisionCheck: IdentifierCollisionCheck,
    private val usernameGenerator: UsernameGenerator,
    private val passwordPolicy: PasswordPolicyPort? = null,
    private val emailPort: EmailPort? = null,
) {
    private companion object {
        /** Shared by [createUser] and [updateUser] so the two paths cannot drift apart. */
        val USERNAME_PATTERN = Regex("[a-zA-Z0-9._@+-]+")
    }

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

        if (email.isBlank() || !email.contains('@')) {
            return AdminResult.Failure(AdminError.Validation("A valid email address is required."))
        }
        val resolvedEmail = email.trim().lowercase()

        val resolvedUsername =
            if (username.isBlank()) {
                usernameGenerator.generate(tenantId, givenName, resolvedEmail)
            } else {
                username.trim()
            }
        if (!resolvedUsername.matches(USERNAME_PATTERN)) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "Username may only contain letters, digits, dots, underscores, hyphens, @, and +.",
                ),
            )
        }

        collisionCheck
            .check(tenantId, resolvedUsername, resolvedEmail)
            ?.let { return AdminResult.Failure(AdminError.Validation(it)) }

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

        if (userRepository.existsByUsername(tenantId, resolvedUsername)) {
            return AdminResult.Failure(AdminError.Conflict("Username '$resolvedUsername' is already taken."))
        }
        if (userRepository.existsByEmail(tenantId, resolvedEmail)) {
            return AdminResult.Failure(AdminError.Conflict("Email '$resolvedEmail' is already registered."))
        }

        val user =
            userRepository.save(
                User(
                    tenantId = tenantId,
                    username = resolvedUsername,
                    email = resolvedEmail,
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
                details = mapOf("username" to resolvedUsername, "invite" to sendInvite.toString()),
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
        username: String? = null,
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

        val resolvedUsername =
            when (val r = resolveUsername(tenantId, userId, user, username, resolvedEmail)) {
                is AdminResult.Failure -> return r
                is AdminResult.Success -> r.value
            }

        val updated =
            userRepository.update(
                user.copy(email = resolvedEmail, fullName = resolvedFullName, username = resolvedUsername),
            )

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.ADMIN_USER_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = usernameChangeAuditDetails(user, resolvedUsername),
            ),
        )

        return AdminResult.Success(updated)
    }

    /**
     * Replaces the full mutable profile in one write. Unlike [updateUser], `email`, `fullName`,
     * `externalId`, `givenName`, and `familyName` are all authoritative — a null clears the field.
     * `username` alone keeps [updateUser]'s null-means-unchanged convention, since SCIM never
     * supplies it (`userName` stays immutable over the SCIM protocol surface — see
     * ScimDiscoveryRoutes) and only the admin UI's profile form does, alongside the rest of the
     * profile, so a rename cannot land as two separate writes with two different failure windows.
     */
    fun replaceUserProfile(
        userId: UserId,
        tenantId: TenantId,
        email: String,
        fullName: String,
        externalId: String?,
        givenName: String?,
        familyName: String?,
        username: String? = null,
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

        val resolvedUsername =
            when (val r = resolveUsername(tenantId, userId, user, username, resolvedEmail)) {
                is AdminResult.Failure -> return r
                is AdminResult.Success -> r.value
            }

        val updated =
            userRepository.update(
                user.copy(
                    email = resolvedEmail,
                    fullName = fullName.trim(),
                    externalId = externalId,
                    givenName = givenName,
                    familyName = familyName,
                    username = resolvedUsername,
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
                details = usernameChangeAuditDetails(user, resolvedUsername),
            ),
        )

        return AdminResult.Success(updated)
    }

    /**
     * Shared by [updateUser] and [replaceUserProfile]: resolves an optional username edit
     * (null means unchanged), validates format, and rejects a collision with another user's
     * username or email — the two call sites cannot drift apart on what a rename is allowed to do.
     */
    private fun resolveUsername(
        tenantId: TenantId,
        userId: UserId,
        user: User,
        username: String?,
        resolvedEmail: String,
    ): AdminResult<String> {
        val resolvedUsername = username?.trim() ?: user.username

        if (resolvedUsername.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Username is required."))
        }
        if (!resolvedUsername.matches(USERNAME_PATTERN)) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "Username may only contain letters, digits, dots, underscores, hyphens, @, and +.",
                ),
            )
        }
        if (resolvedUsername != user.username && userRepository.existsByUsername(tenantId, resolvedUsername)) {
            return AdminResult.Failure(AdminError.Conflict("Username '$resolvedUsername' is already taken."))
        }

        collisionCheck
            .check(tenantId, resolvedUsername, resolvedEmail, excludingUserId = userId)
            ?.let { return AdminResult.Failure(AdminError.Validation(it)) }

        return AdminResult.Success(resolvedUsername)
    }

    /**
     * The pre-rename username always identifies which row was touched. When a rename actually
     * happened, the new value is added alongside it so an investigator can tell a username changed
     * — and to what — without diffing database snapshots.
     */
    private fun usernameChangeAuditDetails(
        user: User,
        resolvedUsername: String,
    ): Map<String, String> =
        if (resolvedUsername != user.username) {
            mapOf("username" to user.username, "newUsername" to resolvedUsername)
        } else {
            mapOf("username" to user.username)
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
