package com.kauth.adapter.persistence

import com.kauth.domain.model.User
import com.kauth.domain.port.UserRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Persistence adapter — implements the UserRepository port using PostgreSQL + Exposed.
 *
 * This is the only place in the codebase that knows about SQL.
 * If we ever switch to MongoDB, only this class changes. The domain, service,
 * and web layers are completely insulated.
 */
class PostgresUserRepository : UserRepository {

    override fun findByUsername(username: String): User? = transaction {
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .map { it.toUser() }
            .singleOrNull()
    }

    override fun findByEmail(email: String): User? = transaction {
        UsersTable.selectAll()
            .where { UsersTable.email eq email.lowercase() }
            .map { it.toUser() }
            .singleOrNull()
    }

    override fun save(user: User): User = transaction {
        val insertedId = UsersTable.insert {
            it[username] = user.username
            it[email] = user.email.lowercase()
            it[passwordHash] = user.passwordHash
            it[fullName] = user.fullName
        } get UsersTable.id

        user.copy(id = insertedId)
    }

    override fun existsByUsername(username: String): Boolean = transaction {
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .count() > 0
    }

    override fun existsByEmail(email: String): Boolean = transaction {
        UsersTable.selectAll()
            .where { UsersTable.email eq email.lowercase() }
            .count() > 0
    }

    /**
     * Extension function to map a ResultRow to the domain User entity.
     * Mapping logic stays in the adapter — the domain model stays clean.
     */
    private fun ResultRow.toUser(): User = User(
        id = this[UsersTable.id],
        username = this[UsersTable.username],
        email = this[UsersTable.email],
        passwordHash = this[UsersTable.passwordHash],
        fullName = this[UsersTable.fullName]
    )
}
