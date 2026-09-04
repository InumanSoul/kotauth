package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Session
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.PasswordPolicyPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.sha256Hex
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
 *   slug → Tenant → resolve User by the workspace's identifier mode (username, email, or
 *   either — see UserIdentifierResolver) in that tenant → verify password → tokens + session
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
    private val credentialFlowService: CredentialFlowService? = null,
    private val passwordPolicy: PasswordPolicyPort? = null,
    private val applicationRepository: ApplicationRepository? = null,
    private val roleRepository: RoleRepository? = null,
    private val identifierResolver: UserIdentifierResolver,
    private val collisionCheck: IdentifierCollisionCheck,
) {
    // Equalises latency so wrong-password vs. user-not-found / disabled / locked / pending-setup
    // are indistinguishable timing-wise — closes the bcrypt-skipped enumeration vector.
    private val timingEqualizationHash: String by lazy { passwordHasher.hash(TIMING_EQUALIZATION_PROBE) }

    private fun runDummyVerify(rawPassword: String) {
        passwordHasher.verify(rawPassword, timingEqualizationHash)
    }

    private companion object {
        const val TIMING_EQUALIZATION_PROBE = "__kauth_timing_equalization_probe__"
    }

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
        baseUrl: String? = null,
    ): AuthResult<User> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return AuthResult.Failure(AuthError.TenantNotFound)

        if (!tenant.securityConfig.passwordLoginEnabled) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = null,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_REJECTED_POLICY,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    details = mapOf("reason" to "password_login_disabled"),
                ),
            )
            return AuthResult.Failure(AuthError.PasswordLoginDisabled)
        }

        if (username.isBlank() || rawPassword.isBlank()) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        val resolution =
            identifierResolver.resolve(
                tenant.id,
                tenant.securityConfig.loginIdentifierMode,
                username,
            )
        if (resolution is IdentifierResolution.Ambiguous) {
            runDummyVerify(rawPassword)
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = null,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_FAILED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    details = mapOf("reason" to "ambiguous_identifier"),
                ),
            )
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }
        val user = (resolution as? IdentifierResolution.Found)?.user
        if (user == null) {
            runDummyVerify(rawPassword)
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
            runDummyVerify(rawPassword)
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

        val security = tenant.securityConfig
        if (security.isLockoutEnabled && user.isLocked) {
            runDummyVerify(rawPassword)
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = user.id,
                    clientId = null,
                    eventType = AuditEventType.ACCOUNT_LOCKED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    details = mapOf("reason" to "still_locked"),
                ),
            )
            return AuthResult.Failure(AuthError.AccountLocked(user.lockedUntil!!))
        }

        if (RequiredAction.SET_PASSWORD in user.requiredActions) {
            runDummyVerify(rawPassword)
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = user.id,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_FAILED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    details = mapOf("reason" to "pending_setup"),
                ),
            )
            return AuthResult.Failure(AuthError.PendingSetup)
        }

        if (!passwordHasher.verify(rawPassword, user.passwordHash)) {
            // Increment failed attempt counter and apply lockout if threshold reached
            if (security.isLockoutEnabled) {
                val newCount = user.failedLoginAttempts + 1
                val lockUntil =
                    if (newCount >= security.lockoutMaxAttempts) {
                        Instant.now().plusSeconds(security.lockoutDurationMinutes * 60L)
                    } else {
                        null
                    }
                userRepository.recordFailedLogin(user.id!!, newCount, lockUntil)
                if (lockUntil != null) {
                    auditLog.record(
                        AuditEvent(
                            tenantId = tenant.id,
                            userId = user.id,
                            clientId = null,
                            eventType = AuditEventType.ACCOUNT_LOCKED,
                            ipAddress = ipAddress,
                            userAgent = userAgent,
                            details = mapOf("attempts" to newCount.toString()),
                        ),
                    )
                    credentialFlowService?.sendAccountLockedNotification(user, tenant, baseUrl ?: "")
                }
            }
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

        // Admin-initiated forced password change — checked AFTER password verify so
        // attackers cannot enumerate which users have the CHANGE_PASSWORD flag by
        // observing response differences on invalid credentials. Unlike SET_PASSWORD
        // (sentinel hash), these users have a real password and authenticate normally
        // before being routed to the change-password page.
        if (RequiredAction.CHANGE_PASSWORD in user.requiredActions) {
            auditLog.record(
                AuditEvent(
                    tenantId = tenant.id,
                    userId = user.id,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_FAILED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    details = mapOf("reason" to "password_change_required"),
                ),
            )
            return AuthResult.Failure(AuthError.PasswordChangeRequired)
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

        // Reset failed login counter on successful authentication
        if (security.isLockoutEnabled && user.failedLoginAttempts > 0) {
            userRepository.resetFailedLogins(user.id!!)
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
     * Used for direct (non-OAuth) browser login and REST token issuance.
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
                accessTokenHash = sha256Hex(tokens.access_token),
                refreshTokenHash = tokens.refresh_token?.let { sha256Hex(it) },
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
        /**
         * The `client_id` of the OAuth client whose `/authorize` flow this
         * registration originated from. When set, that client's configured
         * default roles are granted to the new user. Null for non-OAuth
         * registrations (no defaults are granted).
         */
        originatingClientId: String? = null,
    ): AuthResult<User> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return AuthResult.Failure(AuthError.TenantNotFound)

        if (!tenant.registrationEnabled) {
            return AuthResult.Failure(AuthError.RegistrationDisabled)
        }

        // Passwordless tenants: registration is allowed only if magic-link is on,
        // since the user has no other email-based path to actually sign in.
        val passwordlessMode = !tenant.securityConfig.passwordLoginEnabled
        if (passwordlessMode && !tenant.securityConfig.magicLinkEnabled) {
            return AuthResult.Failure(AuthError.PasswordLoginDisabled)
        }

        val coreFieldsBlank = username.isBlank() || email.isBlank() || fullName.isBlank()
        if (coreFieldsBlank || (!passwordlessMode && rawPassword.isBlank())) {
            return AuthResult.Failure(AuthError.ValidationError("All fields are required."))
        }

        if (!email.contains("@")) {
            return AuthResult.Failure(AuthError.ValidationError("Please enter a valid email address."))
        }

        // Normalize FIRST, then validate — "Dave" becomes "dave" and is accepted, while
        // "john doe" is rejected rather than silently rewritten. This was previously the only
        // write path with no username format validation at all.
        val normalizedUsername = UsernamePolicy.normalize(username)
        val normalizedEmail = email.trim().lowercase()

        if (!normalizedUsername.matches(UsernamePolicy.USERNAME_PATTERN)) {
            return AuthResult.Failure(
                AuthError.ValidationError(
                    "Username may only contain letters, digits, dots, underscores, hyphens, @, and +.",
                ),
            )
        }

        // In passwordless mode the password is generated server-side and never used by
        // the user — skip policy/confirmation checks. The User table still requires a
        // hash, so we mint a random unguessable secret to satisfy the schema.
        val effectivePassword =
            if (passwordlessMode) {
                generateSyntheticPassword()
            } else {
                val policyError = passwordPolicy?.validate(rawPassword, tenant)
                if (policyError != null) {
                    return AuthResult.Failure(AuthError.ValidationError(policyError))
                } else if (passwordPolicy == null && rawPassword.length < tenant.passwordPolicyMinLength) {
                    return AuthResult.Failure(AuthError.WeakPassword(tenant.passwordPolicyMinLength))
                }
                if (rawPassword != confirmPassword) {
                    return AuthResult.Failure(AuthError.ValidationError("Passwords do not match."))
                }
                rawPassword
            }

        if (userRepository.existsByUsername(tenant.id, normalizedUsername)) {
            return AuthResult.Failure(AuthError.UserAlreadyExists)
        }

        if (userRepository.existsByEmail(tenant.id, normalizedEmail)) {
            return AuthResult.Failure(AuthError.EmailAlreadyExists)
        }

        // Same-namespace duplicates are ruled out above; this catches the cross-namespace
        // pair — this username equal to a DIFFERENT user's email, or vice versa — that the
        // database's separate unique constraints would otherwise allow through.
        collisionCheck
            .check(tenant.id, normalizedUsername, normalizedEmail)
            ?.let { return AuthResult.Failure(AuthError.ValidationError(it)) }

        val newUser =
            User(
                tenantId = tenant.id,
                username = normalizedUsername,
                email = normalizedEmail,
                fullName = fullName.trim(),
                passwordHash = passwordHasher.hash(effectivePassword),
            )

        val savedUser = userRepository.save(newUser)

        if (!passwordlessMode && passwordPolicy != null && tenant.passwordPolicyHistoryCount > 0) {
            passwordPolicy.recordPasswordHistory(savedUser.id!!, tenant.id, newUser.passwordHash)
        }

        grantClientDefaultRoles(tenant.id, savedUser.id!!, originatingClientId)

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

        if (tenant.emailVerificationRequired && tenant.isSmtpReady && credentialFlowService != null) {
            try {
                credentialFlowService.initiateEmailVerification(savedUser.id, tenant.id, baseUrl)
            } catch (_: Exception) {
                // non-fatal
            }
        }

        return AuthResult.Success(savedUser)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun grantClientDefaultRoles(
        tenantId: TenantId,
        userId: UserId,
        originatingClientId: String?,
    ) = applyClientDefaultRolesGrant(
        tenantId,
        userId,
        originatingClientId,
        applicationRepository,
        roleRepository,
    )

    // Two UUIDs concatenated — 256 bits of entropy. Used as the stored hash for
    // passwordless registrations; the user never sees or types this value.
    private fun generateSyntheticPassword(): String {
        val a = java.util.UUID.randomUUID()
        val b = java.util.UUID.randomUUID()
        return "$a$b"
    }

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
     * The user's password has exceeded the tenant's `passwordPolicyMaxAgeDays` limit.
     * The user must reset their password before they can log in.
     * We surface this explicitly (rather than as InvalidCredentials) so the UI can
     * direct the user to the forgot-password flow with an actionable message.
     */
    object PasswordExpired : AuthError()

    /** The account has been locked after too many failed login attempts. */
    class AccountLocked(
        @Suppress("unused") val lockedUntil: Instant,
    ) : AuthError()

    /** The user was created via invite and has not yet set a password. */
    object PendingSetup : AuthError()

    /**
     * Admin forced a password change. Surface to the web adapter so it can
     * redirect the user to the change-password page instead of issuing tokens.
     */
    object PasswordChangeRequired : AuthError()

    /** Tenant policy disables password authentication. Surface so the UI can direct the user to passwordless paths. */
    object PasswordLoginDisabled : AuthError()
}
