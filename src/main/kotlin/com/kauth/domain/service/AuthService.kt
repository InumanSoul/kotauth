package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository

/**
 * Application use cases for authentication — tenant-scoped.
 *
 * Every operation begins by resolving the tenant slug to a Tenant entity.
 * If the tenant doesn't exist, all operations fail with TenantNotFound.
 * This is the correct security posture: a non-existent tenant is
 * indistinguishable from a wrong password — no slug enumeration leaks.
 *
 * Flow — login:
 *   slug → Tenant → find User by username in that tenant → verify password → tokens
 *
 * Flow — register:
 *   slug → Tenant → check policy → validate → hash password → save User
 */
class AuthService(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val tokenPort: TokenPort,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort
) {

    /**
     * Authenticates a user and returns the User domain object.
     * Records LOGIN_SUCCESS or LOGIN_FAILED audit events.
     * Does NOT issue tokens — use this when the caller needs to decide
     * what to do after authentication (e.g. issue code vs issue tokens directly).
     */
    fun authenticate(
        tenantSlug: String,
        username: String,
        rawPassword: String,
        ipAddress: String? = null,
        userAgent: String? = null
    ): AuthResult<User> {
        val tenant = tenantRepository.findBySlug(tenantSlug)
            ?: return AuthResult.Failure(AuthError.TenantNotFound)

        if (username.isBlank() || rawPassword.isBlank()) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        val user = userRepository.findByUsername(tenant.id, username)
        if (user == null) {
            auditLog.record(AuditEvent(
                tenantId  = tenant.id,
                userId    = null,
                clientId  = null,
                eventType = AuditEventType.LOGIN_FAILED,
                ipAddress = ipAddress,
                userAgent = userAgent
            ))
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        if (!user.enabled) {
            auditLog.record(AuditEvent(
                tenantId  = tenant.id,
                userId    = user.id,
                clientId  = null,
                eventType = AuditEventType.LOGIN_FAILED,
                ipAddress = ipAddress,
                userAgent = userAgent
            ))
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        if (!passwordHasher.verify(rawPassword, user.passwordHash)) {
            auditLog.record(AuditEvent(
                tenantId  = tenant.id,
                userId    = user.id,
                clientId  = null,
                eventType = AuditEventType.LOGIN_FAILED,
                ipAddress = ipAddress,
                userAgent = userAgent
            ))
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        auditLog.record(AuditEvent(
            tenantId  = tenant.id,
            userId    = user.id,
            clientId  = null,
            eventType = AuditEventType.LOGIN_SUCCESS,
            ipAddress = ipAddress,
            userAgent = userAgent
        ))
        return AuthResult.Success(user)
    }

    /**
     * Authenticates a user and immediately issues a token set (legacy / direct flow).
     * For OAuth2 Authorization Code Flow, use [authenticate] + OAuthService instead.
     * Note: does NOT record audit events — callers should call [authenticate] first
     * which records the LOGIN event. Used by admin console login and legacy direct-login.
     */
    fun login(tenantSlug: String, username: String, rawPassword: String): AuthResult<TokenResponse> {
        val tenant = tenantRepository.findBySlug(tenantSlug)
            ?: return AuthResult.Failure(AuthError.TenantNotFound)

        if (username.isBlank() || rawPassword.isBlank()) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        val user = userRepository.findByUsername(tenant.id, username)
            ?: return AuthResult.Failure(AuthError.InvalidCredentials)

        if (!user.enabled) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        if (!passwordHasher.verify(rawPassword, user.passwordHash)) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        return AuthResult.Success(tokenPort.issueUserTokens(
            user   = user,
            tenant = tenant,
            client = null,
            scopes = listOf("openid")
        ))
    }

    /**
     * Registers a new user within the given tenant.
     * Respects the tenant's registration policy and password requirements.
     */
    fun register(
        tenantSlug: String,
        username: String,
        email: String,
        fullName: String,
        rawPassword: String,
        confirmPassword: String
    ): AuthResult<User> {
        val tenant = tenantRepository.findBySlug(tenantSlug)
            ?: return AuthResult.Failure(AuthError.TenantNotFound)

        if (!tenant.registrationEnabled) {
            return AuthResult.Failure(AuthError.RegistrationDisabled)
        }

        if (username.isBlank() || email.isBlank() || fullName.isBlank() || rawPassword.isBlank()) {
            return AuthResult.Failure(AuthError.ValidationError("All fields are required."))
        }

        if (!email.contains("@")) {
            return AuthResult.Failure(AuthError.ValidationError("Please enter a valid email address."))
        }

        if (rawPassword.length < tenant.passwordPolicyMinLength) {
            return AuthResult.Failure(AuthError.WeakPassword(tenant.passwordPolicyMinLength))
        }

        if (rawPassword != confirmPassword) {
            return AuthResult.Failure(AuthError.ValidationError("Passwords do not match."))
        }

        if (userRepository.existsByUsername(tenant.id, username)) {
            return AuthResult.Failure(AuthError.UserAlreadyExists)
        }

        if (userRepository.existsByEmail(tenant.id, email)) {
            return AuthResult.Failure(AuthError.EmailAlreadyExists)
        }

        val newUser = User(
            tenantId = tenant.id,
            username = username.trim(),
            email = email.trim().lowercase(),
            fullName = fullName.trim(),
            passwordHash = passwordHasher.hash(rawPassword)
        )

        return AuthResult.Success(userRepository.save(newUser))
    }
}

/**
 * Discriminated union for auth operation results.
 * Avoids exception-based flow control across layer boundaries.
 */
sealed class AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>()
    data class Failure(val error: AuthError) : AuthResult<Nothing>()
}

/**
 * Typed errors the domain can produce.
 * The web adapter maps these to HTTP status codes or UI error messages.
 */
sealed class AuthError {
    /** Credentials don't match — vague by design to prevent user enumeration. */
    object InvalidCredentials : AuthError()

    /** The requested tenant slug does not exist. */
    object TenantNotFound : AuthError()

    /** Self-registration is disabled on this tenant. */
    object RegistrationDisabled : AuthError()

    /** Username is already taken within this tenant. */
    object UserAlreadyExists : AuthError()

    /** Email is already registered within this tenant. */
    object EmailAlreadyExists : AuthError()

    /** Password doesn't meet the tenant's minimum length policy. */
    data class WeakPassword(val minLength: Int) : AuthError()

    /** Generic validation failure with a human-readable message. */
    data class ValidationError(val message: String) : AuthError()
}
