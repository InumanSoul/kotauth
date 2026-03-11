package com.kauth.domain.service

import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
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
    private val passwordHasher: PasswordHasher
) {

    /**
     * Authenticates a user within the given tenant and returns a token set on success.
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

        return AuthResult.Success(tokenPort.createTokenSet(user.username))
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
