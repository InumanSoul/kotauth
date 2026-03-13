package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Application
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import java.security.SecureRandom
import java.util.Base64

/**
 * Domain service — admin console use cases.
 *
 * Handles all write operations initiated by a platform administrator via the
 * admin console. Pure business logic only: validates, delegates to ports,
 * records audit events. No HTTP concerns here.
 *
 * Discriminated-union results ([AdminResult]) propagate errors explicitly
 * without exceptions, keeping the web adapter free of try/catch noise.
 */
class AdminService(
    private val tenantRepository      : TenantRepository,
    private val userRepository        : UserRepository,
    private val applicationRepository : ApplicationRepository,
    private val passwordHasher        : PasswordHasher,
    private val auditLog              : AuditLogPort
) {

    // =========================================================================
    // Workspace settings
    // =========================================================================

    /**
     * Updates all mutable workspace settings. Slug is immutable.
     * Returns [AdminResult.Failure] with a user-visible message on validation errors.
     */
    fun updateWorkspaceSettings(
        slug                      : String,
        displayName               : String,
        issuerUrl                 : String?,
        tokenExpirySeconds        : Long,
        refreshTokenExpirySeconds : Long,
        registrationEnabled       : Boolean,
        emailVerificationRequired : Boolean,
        passwordPolicyMinLength   : Int,
        passwordPolicyRequireSpecial: Boolean,
        themeAccentColor          : String,
        themeLogoUrl              : String?,
        themeFaviconUrl           : String?
    ): AdminResult<Tenant> {
        val tenant = tenantRepository.findBySlug(slug)
            ?: return AdminResult.Failure(AdminError.NotFound("Workspace '$slug' not found."))

        if (displayName.isBlank())
            return AdminResult.Failure(AdminError.Validation("Display name is required."))
        if (tokenExpirySeconds < 60)
            return AdminResult.Failure(AdminError.Validation("Token expiry must be at least 60 seconds."))
        if (refreshTokenExpirySeconds < tokenExpirySeconds)
            return AdminResult.Failure(AdminError.Validation("Refresh token expiry must be ≥ access token expiry."))
        if (passwordPolicyMinLength < 4 || passwordPolicyMinLength > 128)
            return AdminResult.Failure(AdminError.Validation("Password minimum length must be between 4 and 128."))

        val updated = tenant.copy(
            displayName               = displayName.trim(),
            issuerUrl                 = issuerUrl?.trim()?.takeIf { it.isNotBlank() },
            tokenExpirySeconds        = tokenExpirySeconds,
            refreshTokenExpirySeconds = refreshTokenExpirySeconds,
            registrationEnabled       = registrationEnabled,
            emailVerificationRequired = emailVerificationRequired,
            passwordPolicyMinLength   = passwordPolicyMinLength,
            passwordPolicyRequireSpecial = passwordPolicyRequireSpecial,
            theme = tenant.theme.copy(
                accentColor = themeAccentColor.trim().ifBlank { TenantTheme.DEFAULT.accentColor },
                logoUrl     = themeLogoUrl?.trim()?.takeIf { it.isNotBlank() },
                faviconUrl  = themeFaviconUrl?.trim()?.takeIf { it.isNotBlank() }
            )
        )

        val saved = tenantRepository.update(updated)

        auditLog.record(AuditEvent(
            tenantId  = tenant.id,
            userId    = null,
            clientId  = null,
            eventType = AuditEventType.ADMIN_TENANT_UPDATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("slug" to slug, "displayName" to displayName)
        ))

        return AdminResult.Success(saved)
    }

    // =========================================================================
    // User management
    // =========================================================================

    /**
     * Creates a new user in the specified tenant. Unlike self-registration,
     * the admin can set any password — no minimum length policy applies here
     * beyond a hard floor of 4 characters.
     */
    fun createUser(
        tenantId : Int,
        username : String,
        email    : String,
        fullName : String,
        password : String
    ): AdminResult<User> {
        if (username.isBlank())
            return AdminResult.Failure(AdminError.Validation("Username is required."))
        if (!username.matches(Regex("[a-zA-Z0-9._-]+")))
            return AdminResult.Failure(AdminError.Validation("Username may only contain letters, digits, dots, underscores, and hyphens."))
        if (email.isBlank() || !email.contains('@'))
            return AdminResult.Failure(AdminError.Validation("A valid email address is required."))
        if (password.length < 4)
            return AdminResult.Failure(AdminError.Validation("Password must be at least 4 characters."))
        if (userRepository.existsByUsername(tenantId, username))
            return AdminResult.Failure(AdminError.Conflict("Username '$username' is already taken."))
        if (userRepository.existsByEmail(tenantId, email))
            return AdminResult.Failure(AdminError.Conflict("Email '${email.lowercase()}' is already registered."))

        val user = userRepository.save(User(
            tenantId     = tenantId,
            username     = username.trim(),
            email        = email.trim(),
            fullName     = fullName.trim(),
            passwordHash = passwordHasher.hash(password),
            emailVerified = true,   // admin-created users are considered verified
            enabled      = true
        ))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = user.id,
            clientId  = null,
            eventType = AuditEventType.ADMIN_USER_CREATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("username" to username)
        ))

        return AdminResult.Success(user)
    }

    /**
     * Updates a user's mutable profile fields (email, fullName).
     * Username is intentionally immutable — it may appear in tokens already issued.
     */
    fun updateUser(
        userId   : Int,
        tenantId : Int,
        email    : String,
        fullName : String
    ): AdminResult<User> {
        val user = userRepository.findById(userId)
            ?: return AdminResult.Failure(AdminError.NotFound("User $userId not found."))

        if (user.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("User $userId not found in this workspace."))
        if (email.isBlank() || !email.contains('@'))
            return AdminResult.Failure(AdminError.Validation("A valid email address is required."))

        // Check email uniqueness only if it changed
        val newEmail = email.trim().lowercase()
        if (newEmail != user.email && userRepository.existsByEmail(tenantId, newEmail))
            return AdminResult.Failure(AdminError.Conflict("Email '$newEmail' is already registered."))

        val updated = userRepository.update(user.copy(email = newEmail, fullName = fullName.trim()))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = userId,
            clientId  = null,
            eventType = AuditEventType.ADMIN_USER_UPDATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("username" to user.username)
        ))

        return AdminResult.Success(updated)
    }

    /**
     * Enables or disables a user account.
     */
    fun setUserEnabled(
        userId   : Int,
        tenantId : Int,
        enabled  : Boolean
    ): AdminResult<Unit> {
        val user = userRepository.findById(userId)
            ?: return AdminResult.Failure(AdminError.NotFound("User $userId not found."))

        if (user.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("User $userId not found in this workspace."))

        userRepository.update(user.copy(enabled = enabled))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = userId,
            clientId  = null,
            eventType = if (enabled) AuditEventType.ADMIN_USER_ENABLED else AuditEventType.ADMIN_USER_DISABLED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("username" to user.username, "enabled" to enabled.toString())
        ))

        return AdminResult.Success(Unit)
    }

    // =========================================================================
    // Application management
    // =========================================================================

    /**
     * Updates mutable application fields. clientId is immutable.
     */
    fun updateApplication(
        appId        : Int,
        tenantId     : Int,
        name         : String,
        description  : String?,
        accessType   : String,
        redirectUris : List<String>
    ): AdminResult<Application> {
        val app = applicationRepository.findById(appId)
            ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        if (name.isBlank())
            return AdminResult.Failure(AdminError.Validation("Name is required."))

        val updated = applicationRepository.update(
            appId        = appId,
            name         = name.trim(),
            description  = description?.trim()?.takeIf { it.isNotBlank() },
            accessType   = accessType,
            redirectUris = redirectUris
        )

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = appId,
            eventType = AuditEventType.ADMIN_CLIENT_UPDATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("clientId" to app.clientId)
        ))

        return AdminResult.Success(updated)
    }

    /**
     * Enables or disables an application.
     */
    fun setApplicationEnabled(
        appId    : Int,
        tenantId : Int,
        enabled  : Boolean
    ): AdminResult<Unit> {
        val app = applicationRepository.findById(appId)
            ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))

        applicationRepository.setEnabled(appId, enabled)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = appId,
            eventType = if (enabled) AuditEventType.ADMIN_CLIENT_ENABLED else AuditEventType.ADMIN_CLIENT_DISABLED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("clientId" to app.clientId, "enabled" to enabled.toString())
        ))

        return AdminResult.Success(Unit)
    }

    /**
     * Generates a new client secret for a confidential application.
     * Returns the raw secret — it will NOT be shown again.
     * Only the bcrypt hash is persisted.
     */
    fun regenerateClientSecret(
        appId    : Int,
        tenantId : Int
    ): AdminResult<String> {
        val app = applicationRepository.findById(appId)
            ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))

        // Generate 32-byte (256-bit) cryptographically random secret, base64url encoded
        val raw   = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

        applicationRepository.setClientSecretHash(appId, passwordHasher.hash(secret))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = appId,
            eventType = AuditEventType.ADMIN_CLIENT_SECRET_REGENERATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("clientId" to app.clientId)
        ))

        return AdminResult.Success(secret)
    }
}

// =============================================================================
// Result types
// =============================================================================

sealed class AdminResult<out T> {
    data class Success<T>(val value: T) : AdminResult<T>()
    data class Failure(val error: AdminError) : AdminResult<Nothing>()
}

sealed class AdminError(val message: String) {
    class NotFound(message: String)    : AdminError(message)
    class Conflict(message: String)    : AdminError(message)
    class Validation(message: String)  : AdminError(message)
}
