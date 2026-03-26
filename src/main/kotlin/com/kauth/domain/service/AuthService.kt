package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Application use cases for authentication — tenant-scoped.
 *
 * Every operation begins by resolving the tenant slug to a Tenant entity.
 * If the tenant doesn't exist, all operations fail with TenantNotFound.
 * This is the correct security posture: a non-existent tenant is
 * indistinguishable from a wrong password — no slug enumeration leaks.
 *
 * Flow — login:
 *   slug → Tenant → find User by username in that tenant → verify password → tokens + session
 *
 * Flow — register:
 *   slug → Tenant → check policy → validate → hash password → save User
 */
class AuthService(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val tokenPort: TokenPort,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val sessionRepository: SessionRepository,
    private val selfServiceService: UserSelfServiceService? = null,
    private val passwordPolicy: PasswordPolicyPort? = null,
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
        userAgent: String? = null,
    ): AuthResult<User> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return AuthResult.Failure(AuthError.TenantNotFound)

        if (username.isBlank() || rawPassword.isBlank()) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        val user = userRepository.findByUsername(tenant.id, username)
        if (user == null) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = null,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_FAILED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                ),
            )
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        if (!user.enabled) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = user.id,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_FAILED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                ),
            )
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        if (!passwordHasher.verify(rawPassword, user.passwordHash)) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = user.id,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_FAILED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                ),
            )
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        // Enforce password expiry if configured and the user has a recorded
        // last-change timestamp. Users created before expiry was enabled (null timestamp)
        // are not affected until they next change their password — prevents mass lockouts
        // when an admin first activates the policy on an existing tenant.
        if (tenant.passwordPolicyMaxAgeDays > 0 && user.lastPasswordChangeAt != null) {
            val ageDays = Duration.between(user.lastPasswordChangeAt, Instant.now()).toDays()
            if (ageDays >= tenant.passwordPolicyMaxAgeDays) {
                auditLog.record(
                    AuditEvent(
                        tenantId = tenant.id,
                        userId = user.id,
                        clientId = null,
                        eventType = AuditEventType.LOGIN_FAILED,
                        ipAddress = ipAddress,
                        userAgent = userAgent,
                    ),
                )
                return AuthResult.Failure(AuthError.PasswordExpired)
            }
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.LOGIN_SUCCESS,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )
        return AuthResult.Success(user)
    }

    /**
     * Authenticates a user, issues a token set, and persists a server-side session.
     * Used for direct (non-OAuth) browser login and the admin console.
     *
     * Delegates credential verification to [authenticate] so that validation logic,
     * audit logging, and password expiry checks live in exactly one place.
     *
     * For OAuth2 Authorization Code Flow, prefer [authenticate] + OAuthService
     * which handles client validation, PKCE, and proper redirect handling.
     */
    fun login(
        tenantSlug: String,
        username: String,
        rawPassword: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): AuthResult<TokenResponse> {
        val authResult = authenticate(tenantSlug, username, rawPassword, ipAddress, userAgent)
        if (authResult is AuthResult.Failure) return AuthResult.Failure(authResult.error)

        val user = (authResult as AuthResult.Success).value
        // Safe: authenticate() succeeded, so the tenant exists.
        val tenant = tenantRepository.findBySlug(tenantSlug)!!

        val tokens =
            tokenPort.issueUserTokens(
                user = user,
                tenant = tenant,
                client = null,
                scopes = listOf("openid"),
            )

        sessionRepository.save(
            Session(
                tenantId = tenant.id,
                userId = user.id,
                clientId = null,
                accessTokenHash = sha256(tokens.access_token),
                refreshTokenHash = tokens.refresh_token?.let { sha256(it) },
                scopes = "openid",
                ipAddress = ipAddress,
                userAgent = userAgent,
                expiresAt = Instant.now().plusSeconds(tenant.tokenExpirySeconds),
                refreshExpiresAt =
                    tokens.refresh_token?.let {
                        Instant.now().plusSeconds(tenant.refreshTokenExpirySeconds)
                    },
            ),
        )

        enforceConcurrentSessionLimit(tenant.id, user.id!!, tenant.maxConcurrentSessions)

        return AuthResult.Success(tokens)
    }

    /**
     * Registers a new user within the given tenant.
     * Respects the tenant's registration policy and password requirements.
     * [baseUrl] is used to construct the verification email link.
     */
    fun register(
        tenantSlug: String,
        username: String,
        email: String,
        fullName: String,
        rawPassword: String,
        confirmPassword: String,
        baseUrl: String,
    ): AuthResult<User> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
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

        val policyError = passwordPolicy?.validate(rawPassword, tenant)
        if (policyError != null) {
            return AuthResult.Failure(AuthError.ValidationError(policyError))
        } else if (passwordPolicy == null && rawPassword.length < tenant.passwordPolicyMinLength) {
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

        val newUser =
            User(
                tenantId = tenant.id,
                username = username.trim(),
                email = email.trim().lowercase(),
                fullName = fullName.trim(),
                passwordHash = passwordHasher.hash(rawPassword),
            )

        val savedUser = userRepository.save(newUser)

        if (passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            passwordPolicy.recordPasswordHistory(savedUser.id!!, tenant.id, newUser.passwordHash)
        }

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = savedUser.id,
                clientId = null,
                eventType = AuditEventType.REGISTER_SUCCESS,
                ipAddress = null,
                userAgent = null,
            ),
        )

        if (tenant.emailVerificationRequired && tenant.isSmtpReady && selfServiceService != null) {
            try {
                selfServiceService.initiateEmailVerification(savedUser.id!!, tenant.id, baseUrl)
            } catch (_: Exception) {
                // non-fatal
            }
        }

        return AuthResult.Success(savedUser)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun enforceConcurrentSessionLimit(
        tenantId: TenantId,
        userId: UserId,
        maxSessions: Int?,
    ) {
        if (maxSessions == null || maxSessions <= 0) return
        val active = sessionRepository.countActiveByUser(tenantId, userId)
        if (active > maxSessions) {
            sessionRepository.revokeOldestForUser(tenantId, userId, keepNewest = maxSessions)
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Discriminated union for auth operation results.
 * Avoids exception-based flow control across layer boundaries.
 */
sealed class AuthResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AuthResult<T>()

    data class Failure(
        val error: AuthError,
    ) : AuthResult<Nothing>()
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
    data class WeakPassword(
        val minLength: Int,
    ) : AuthError()

    /** Generic validation failure with a human-readable message. */
    data class ValidationError(
        val message: String,
    ) : AuthError()

    /**
     * The user's password has exceeded the tenant's [passwordPolicyMaxAgeDays] limit.
     * The user must reset their password before they can log in.
     * We surface this explicitly (rather than as InvalidCredentials) so the UI can
     * direct the user to the forgot-password flow with an actionable message.
     */
    object PasswordExpired : AuthError()
}
