package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'password_reset_tokens' table (V11 migration).
 * Schema is Flyway-owned — no constraint declarations here.
 */
object PasswordResetTokensTable : Table("password_reset_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") references UsersTable.id
    val tenantId = integer("tenant_id") references TenantsTable.id
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val ipAddress = varchar("ip_address", 45).nullable()
    val purpose = varchar("purpose", 32).default("PASSWORD_RESET")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
