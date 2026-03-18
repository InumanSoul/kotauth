package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for 'password_history' table (V13 migration).
 */
object PasswordHistoryTable : Table("password_history") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") references UsersTable.id
    val tenantId = integer("tenant_id") references TenantsTable.id
    val passwordHash = varchar("password_hash", 128)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed ORM mapping for 'password_blacklist' table (V13 migration).
 */
object PasswordBlacklistTable : Table("password_blacklist") {
    val id = integer("id").autoIncrement()
    val tenantId = (integer("tenant_id") references TenantsTable.id).nullable()
    val password = varchar("password", 255)

    override val primaryKey = PrimaryKey(id)
}
