package com.kauth.adapter.persistence

import com.kauth.domain.model.User
import com.kauth.domain.port.UserRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Persistence adapter — implements the UserRepository port using PostgreSQL + Exposed.
 *
 * All queries are scoped by tenantId. There is no API surface here that
 * allows cross-tenant access — the constraint is structural, not convention.
 */
class PostgresUserRepository : UserRepository {

    override fun findByUsername(tenantId: Int, username: String): User? = transaction {
        UsersTable.selectAll()
            .where { (UsersTable.tenantId eq tenantId) and (UsersTable.username eq username) }
            .map { it.toUser() }
            .singleOrNull()
    }

    override fun findByEmail(tenantId: Int, email: String): User? = transaction {
        UsersTable.selectAll()
            .where { (UsersTable.tenantId eq tenantId) and (UsersTable.email eq email.lowercase()) }
            .map { it.toUser() }
            .singleOrNull()
    }

    override fun save(user: User): User = transaction {
        val insertedId = UsersTable.insert {
            it[tenantId]     = user.tenantId
            it[username]     = user.username
            it[email]        = user.email.lowercase()
            it[passwordHash] = user.passwordHash
            it[fullName]     = user.fullName
            it[emailVerified] = user.emailVerified
            it[enabled]      = user.enabled
        } get UsersTable.id

        user.copy(id = insertedId)
    }

    override fun existsByUsername(tenantId: Int, username: String): Boolean = transaction {
        UsersTable.selectAll()
            .where { (UsersTable.tenantId eq tenantId) and (UsersTable.username eq username) }
            .count() > 0
    }

    override fun existsByEmail(tenantId: Int, email: String): Boolean = transaction {
        UsersTable.selectAll()
            .where { (UsersTable.tenantId eq tenantId) and (UsersTable.email eq email.lowercase()) }
            .count() > 0
    }

    private fun ResultRow.toUser(): User = User(
        id            = this[UsersTable.id],
        tenantId      = this[UsersTable.tenantId],
        username      = this[UsersTable.username],
        email         = this[UsersTable.email],
        passwordHash  = this[UsersTable.passwordHash],
        fullName      = this[UsersTable.fullName],
        emailVerified = this[UsersTable.emailVerified],
        enabled       = this[UsersTable.enabled]
    )
}
