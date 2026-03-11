package com.kauth.infrastructure

import com.kauth.adapter.persistence.UsersTable
import com.kauth.adapter.token.BcryptPasswordHasher
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Infrastructure concern: database connection and schema initialization.
 *
 * Extracted from main() so the entry point stays minimal.
 * Also handles the admin seed — once we have a proper migration system
 * (Flyway, Liquibase), this logic moves there.
 */
object DatabaseFactory {

    fun init(url: String, user: String, password: String) {
        Database.connect(
            url = url,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        )

        transaction {
            SchemaUtils.createMissingTablesAndColumns(UsersTable)
            seedAdminIfEmpty()
        }
    }

    /**
     * Seeds a default admin user only on a fresh (empty) database.
     * Password is BCrypt-hashed — no more plain text in the DB.
     */
    private fun seedAdminIfEmpty() {
        if (UsersTable.selectAll().empty()) {
            val hasher = BcryptPasswordHasher()
            UsersTable.insert {
                it[username] = "admin"
                it[email] = "admin@kauth.local"
                it[passwordHash] = hasher.hash("changeme123!")
                it[fullName] = "KotAuth Administrator"
            }
        }
    }
}
