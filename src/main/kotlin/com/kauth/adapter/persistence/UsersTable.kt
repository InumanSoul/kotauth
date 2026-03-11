package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed ORM mapping for the 'users' table created by V1 migration.
 *
 * Schema is now owned by Flyway — no uniqueIndex() calls here.
 * Uniqueness constraints (username + email per tenant) live in the SQL migration.
 * Exposed is used purely for querying, not schema management.
 */
object UsersTable : Table("users") {
    val id            = integer("id").autoIncrement()
    val tenantId      = integer("tenant_id") references TenantsTable.id
    val username      = varchar("username", 50)
    val email         = varchar("email", 255)
    val passwordHash  = varchar("password_hash", 128)
    val fullName      = varchar("full_name", 100)
    val emailVerified = bool("email_verified").default(false)
    val enabled       = bool("enabled").default(true)

    override val primaryKey = PrimaryKey(id)
}
