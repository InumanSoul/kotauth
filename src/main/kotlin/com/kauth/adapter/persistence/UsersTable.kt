package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed ORM table definition.
 * This lives in the adapter layer — it's an implementation detail of PostgreSQL persistence.
 * The domain never sees this class.
 *
 * Note on the email column: added with a default empty string so existing rows
 * (like the seeded admin) don't break when the schema is updated via SchemaUtils.
 */
object UsersTable : Table("users") {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 50).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex().default("")
    val passwordHash = varchar("password_hash", 128)
    val fullName = varchar("full_name", 100)
    override val primaryKey = PrimaryKey(id)
}
