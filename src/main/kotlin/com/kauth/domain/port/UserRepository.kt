package com.kauth.domain.port

import com.kauth.domain.model.User

/**
 * Port (outbound) — defines WHAT the domain needs from persistence.
 * The domain doesn't care if this is PostgreSQL, MySQL, or an in-memory map.
 * The adapter layer provides the implementation.
 */
interface UserRepository {
    fun findByUsername(username: String): User?
    fun findByEmail(email: String): User?
    fun save(user: User): User
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean
}
