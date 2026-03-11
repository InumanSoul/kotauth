package com.kauth.domain.port

/**
 * Port (outbound) — abstracts password hashing from the domain.
 * The domain calls hash() and verify() without knowing it's BCrypt underneath.
 * This makes the domain unit-testable without BCrypt as a dependency.
 */
interface PasswordHasher {
    fun hash(rawPassword: String): String
    fun verify(rawPassword: String, hashedPassword: String): Boolean
}
