package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'mfa_enrollments' table (V14 migration).
 *
 * Tracks TOTP enrollments per user. One enrollment per method per user
 * (enforced by UNIQUE constraint in the migration).
 */
object MfaEnrollmentsTable : Table("mfa_enrollments") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") references UsersTable.id
    val tenantId = integer("tenant_id") references TenantsTable.id
    val method = varchar("method", 20).default("totp")
    val secret = text("secret")
    val verified = bool("verified").default(false)
    val enabled = bool("enabled").default(true)
    val createdAt = timestampWithTimeZone("created_at")
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed ORM mapping for the 'mfa_recovery_codes' table (V14 migration).
 *
 * One-time backup codes, BCrypt-hashed. [usedAt] is set when consumed.
 */
object MfaRecoveryCodesTable : Table("mfa_recovery_codes") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") references UsersTable.id
    val tenantId = integer("tenant_id") references TenantsTable.id
    val codeHash = varchar("code_hash", 128)
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
