package com.kauth.domain.service

import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.CorsPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.SecureTokens

class AdminService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val applicationRepository: ApplicationRepository,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val selfServiceService: UserSelfServiceService,
    private val passwordPolicy: PasswordPolicyPort? = null,
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
