package com.kauth.domain.port

import com.kauth.domain.model.User

/**
 * Port (outbound) — defines what the domain needs from user persistence.
 * All queries are scoped by [tenantId] — there are no cross-tenant lookups.
 */
interface UserRepository {
    fun findByUsername(tenantId: Int, username: String): User?
    fun findByEmail(tenantId: Int, email: String): User?
    fun save(user: User): User
    fun existsByUsername(tenantId: Int, username: String): Boolean
    fun existsByEmail(tenantId: Int, email: String): Boolean
}
