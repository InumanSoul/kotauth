package com.kauth.domain.service

import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository

/**
 * Application use cases for authentication.
 *
 * This class is the heart of the hexagon. It has zero framework dependencies —
 * no Ktor, no Exposed, no BCrypt imports. It communicates purely through ports
 * (interfaces), which makes it independently testable and framework-agnostic.
 *
 * Think of it as the rulebook: "here is what login and registration MEAN in
 * this system, regardless of how the data is stored or how it arrives."
 */
class AuthService(
    private val userRepository: UserRepository,
    private val tokenPort: TokenPort,
    private val passwordHasher: PasswordHasher
) {

    /**
     * Authenticates a user and returns a token set on success.
     * Returns a typed error on failure — no exceptions thrown across boundaries.
     */
    fun login(username: String, rawPassword: String): AuthResult<TokenResponse> {
        if (username.isBlank() || rawPassword.isBlank()) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        val user = userRepository.findByUsername(username)
            ?: return AuthResult.Failure(AuthError.InvalidCredentials)

        if (!passwordHasher.verify(rawPassword, user.passwordHash)) {
            return AuthResult.Failure(AuthError.InvalidCredentials)
        }

        return AuthResult.Success(tokenPort.createTokenSet(user.username))
    }

    /**
     * Registers a new user. Validates uniqueness and password strength.
     * Returns the created User on success, or a typed error on failure.
     */
    fun register(
        username: String,
        email: String,
        fullName: String,
        rawPassword: String,
        confirmPassword: String
    ): AuthResult<User> {
        if (username.isBlank() || email.isBlank() || fullName.isBlank() || rawPassword.isBlank()) {
            return AuthResult.Failure(AuthError.ValidationError("All fields are required."))
        }

        if (!email.contains("@")) {
            return AuthResult.Failure(AuthError.ValidationError("Please enter a valid email address."))
        }

        if (rawPassword.length < 8) {
            return AuthResult.Failure(AuthError.WeakPassword)
        }

        if (rawPassword != confirmPassword) {
            return AuthResult.Failure(AuthError.ValidationError("Passwords do not match."))
        }

        if (userRepository.existsByUsername(username)) {
            return AuthResult.Failure(AuthError.UserAlreadyExists)
        }

        if (userRepository.existsByEmail(email)) {
            return AuthResult.Failure(AuthError.EmailAlreadyExists)
        }

        val newUser = User(
            username = username.trim(),
            email = email.trim().lowercase(),
            fullName = fullName.trim(),
            passwordHash = passwordHasher.hash(rawPassword)
        )

        return AuthResult.Success(userRepository.save(newUser))
    }
}

/**
 * A discriminated union for auth operation results.
 * Avoids exception-based flow control across layer boundaries.
 */
sealed class AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>()
    data class Failure(val error: AuthError) : AuthResult<Nothing>()
}

/**
 * Typed errors that the domain can produce.
 * The web adapter translates these into HTTP responses or UI error messages.
 */
sealed class AuthError {
    /** Credentials don't match — intentionally vague to prevent user enumeration. */
    object InvalidCredentials : AuthError()

    /** Username is already taken. */
    object UserAlreadyExists : AuthError()

    /** Email is already registered. */
    object EmailAlreadyExists : AuthError()

    /** Password doesn't meet minimum requirements. */
    object WeakPassword : AuthError()

    /** Generic validation failure with a human-readable message. */
    data class ValidationError(val message: String) : AuthError()
}
