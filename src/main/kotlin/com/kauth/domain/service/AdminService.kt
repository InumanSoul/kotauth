package com.kauth.domain.service

import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.CorsPort
import com.kauth.domain.port.EmailPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.SecureTokens

class AdminService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val applicationRepository: ApplicationRepository,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val sessionRepository: SessionRepository,
    private val selfServiceService: UserSelfServiceService,
    private val passwordPolicy: PasswordPolicyPort? = null,
    private val emailPort: EmailPort? = null,
    private val corsPort: CorsPort? = null,
) {
    private fun invalidateCors(tenantId: TenantId) {
        val port = corsPort ?: return
        tenantRepository.findById(tenantId)?.slug?.let(port::invalidate)
    }

    /**
     * Returns the http(s) origin (scheme://host[:port]) of [url], or null if the
     * URL is not a parseable absolute http/https URL. Used to compare launcher
     * URL origins against registered redirect URI origins.
     */
    private fun extractHttpOrigin(url: String): String? =
        try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme != "http" && scheme != "https") {
                null
            } else if (host.isNullOrBlank()) {
                null
            } else {
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "$scheme://$host$port"
            }
        } catch (_: Exception) {
            null
        }

    fun createUser(
        tenantId: TenantId,
        username: String,
        email: String,
        fullName: String,
        password: String? = null,
        sendInvite: Boolean = false,
        baseUrl: String = "",
    ): AdminResult<User> {
        val tenant =
            tenantRepository.findById(tenantId)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace not found."))

        if (username.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Username is required."))
        }
        if (!username.matches(Regex("[a-zA-Z0-9._-]+"))) {
            return AdminResult.Failure(
                AdminError.Validation("Username may only contain letters, digits, dots, underscores, and hyphens."),
            )
        }
        if (email.isBlank() || !email.contains('@')) {
            return AdminResult.Failure(AdminError.Validation("A valid email address is required."))
        }

        // Resolve password hash and required actions based on invite mode
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
                    emailVerified = !sendInvite, // invite: verified on acceptance
                    enabled = true,
                    requiredActions = resolvedRequiredActions,
                ),
            )

        // Record in password history (only for password-set users)
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

        // Send invite email — user is created regardless of email outcome.
        // The email itself is async (fire-and-forget via emailScope).
        // initiateInvite only fails synchronously if SMTP is not configured,
        // which we already guard above with tenant.isSmtpReady.
        if (sendInvite && tenant.isSmtpReady) {
            when (selfServiceService.initiateInvite(user, tenant, baseUrl)) {
                is SelfServiceResult.Success ->
                    auditLog.record(
                        AuditEvent(
                            tenantId = tenantId,
                            userId = user.id,
                            clientId = null,
                            eventType = AuditEventType.USER_INVITE_SENT,
                            ipAddress = null,
                            userAgent = null,
                            details = mapOf("username" to username),
                        ),
                    )
                is SelfServiceResult.Failure -> { /* SMTP guard above prevents this; log if it happens */ }
            }
        }

        return AdminResult.Success(user)
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

        return when (val result = selfServiceService.initiateInvite(user, tenant, baseUrl)) {
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

    /**
     * Updates a user's mutable profile fields (email, fullName).
     * Username is intentionally immutable — it may appear in tokens already issued.
     */
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
     * Enables or disables a user account.
     */
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

    // =========================================================================
    // Application management
    // =========================================================================

    /**
     * Updates mutable application fields. clientId is immutable.
     */
    fun updateApplication(
        appId: ApplicationId,
        tenantId: TenantId,
        name: String? = null,
        description: String? = null,
        accessType: String? = null,
        redirectUris: List<String>? = null,
        launcherUrl: String? = null,
        iconUrl: String? = null,
        launcherVisible: Boolean? = null,
        launcherDisplayOrder: Int? = null,
        audience: String? = null,
    ): AdminResult<Application> {
        val app =
            applicationRepository.findById(appId)
                ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId) {
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        val resolvedName = name?.trim() ?: app.name
        val resolvedDescription =
            if (description !=
                null
            ) {
                description.trim().takeIf { it.isNotBlank() }
            } else {
                app.description
            }
        val resolvedAccessType = accessType ?: app.accessType.value
        val resolvedRedirectUris = redirectUris ?: app.redirectUris
        val resolvedLauncherUrl =
            if (launcherUrl != null) launcherUrl.trim().takeIf { it.isNotBlank() } else app.launcherUrl
        val resolvedIconUrl =
            if (iconUrl != null) iconUrl.trim().takeIf { it.isNotBlank() } else app.iconUrl
        val resolvedLauncherVisible = launcherVisible ?: app.launcherVisible
        val resolvedLauncherDisplayOrder = launcherDisplayOrder ?: app.launcherDisplayOrder
        val resolvedAudience =
            if (audience != null) audience.trim().takeIf { it.isNotBlank() } else app.audience

        if (resolvedName.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Name is required."))
        }

        if (resolvedRedirectUris.isEmpty()) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "At least one redirect URI is required. The authorization code flow " +
                        "(including email-OTP back-channel exchange) needs a registered URI to bind to.",
                ),
            )
        }

        if (resolvedAudience != null && resolvedAudience.length > 200) {
            return AdminResult.Failure(AdminError.Validation("Token audience must be 200 characters or fewer."))
        }

        // Origin validation prevents a phishing surface where a compromised admin sets the
        // tile to an attacker-controlled host. Mitigation: launcher URL must share origin
        // with one of the application's already-registered redirect URIs.
        if (resolvedLauncherUrl != null) {
            val launcherOrigin =
                extractHttpOrigin(resolvedLauncherUrl)
                    ?: return AdminResult.Failure(
                        AdminError.Validation("Launcher URL must be a valid http or https URL."),
                    )
            val redirectOrigins = resolvedRedirectUris.mapNotNull { extractHttpOrigin(it) }.toSet()
            if (redirectOrigins.isEmpty()) {
                return AdminResult.Failure(
                    AdminError.Validation(
                        "Add at least one redirect URI on the same origin before setting a launcher URL.",
                    ),
                )
            }
            if (launcherOrigin !in redirectOrigins) {
                return AdminResult.Failure(
                    AdminError.Validation(
                        "Launcher URL origin '$launcherOrigin' must match one of the registered redirect URI origins.",
                    ),
                )
            }
        }

        if (resolvedIconUrl != null && extractHttpOrigin(resolvedIconUrl) == null) {
            return AdminResult.Failure(AdminError.Validation("Icon URL must be a valid http or https URL."))
        }

        val updated =
            applicationRepository.update(
                appId = appId,
                name = resolvedName,
                description = resolvedDescription,
                accessType = resolvedAccessType,
                redirectUris = resolvedRedirectUris,
                launcherUrl = resolvedLauncherUrl,
                iconUrl = resolvedIconUrl,
                launcherVisible = resolvedLauncherVisible,
                launcherDisplayOrder = resolvedLauncherDisplayOrder,
                audience = resolvedAudience,
            )

        invalidateCors(tenantId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = appId,
                eventType = AuditEventType.ADMIN_CLIENT_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to app.clientId),
            ),
        )

        return AdminResult.Success(updated)
    }

    /**
     * Enables or disables an application.
     */
    fun setApplicationEnabled(
        appId: ApplicationId,
        tenantId: TenantId,
        enabled: Boolean,
    ): AdminResult<Unit> {
        val app =
            applicationRepository.findById(appId)
                ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId) {
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        applicationRepository.setEnabled(appId, enabled)

        invalidateCors(tenantId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = appId,
                eventType = if (enabled) AuditEventType.ADMIN_CLIENT_ENABLED else AuditEventType.ADMIN_CLIENT_DISABLED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to app.clientId, "enabled" to enabled.toString()),
            ),
        )

        return AdminResult.Success(Unit)
    }

    /**
     * Generates a new client secret for a confidential application.
     * Returns the raw secret — it will NOT be shown again.
     * Only the bcrypt hash is persisted.
     */
    fun regenerateClientSecret(
        appId: ApplicationId,
        tenantId: TenantId,
    ): AdminResult<String> {
        val app =
            applicationRepository.findById(appId)
                ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId) {
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        // Generate 32-byte (256-bit) cryptographically random secret, base64url encoded
        val secret = SecureTokens.randomBase64Url(32)

        applicationRepository.setClientSecretHash(appId, passwordHasher.hash(secret))

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = appId,
                eventType = AuditEventType.ADMIN_CLIENT_SECRET_REGENERATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to app.clientId),
            ),
        )

        return AdminResult.Success(secret)
    }

    // =========================================================================
    // SMTP configuration
    // =========================================================================

    /**
     * Updates SMTP configuration for a workspace.
     * [smtpPassword] should be the raw (plaintext) value — the adapter layer
     * encrypts it before persistence via the infrastructure EncryptionService.
     */
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
