package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'email_verification_tokens' table (V10 migration).
 * Schema is Flyway-owned — no constraint declarations here.
 */
object EmailVerificationTokensTable : Table("email_verification_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") references UsersTable.id
    val tenantId = integer("tenant_id") references TenantsTable.id
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
