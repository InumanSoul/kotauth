package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'social_accounts' table (V18 migration).
 */
object SocialAccountsTable : Table("social_accounts") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") references UsersTable.id
    val tenantId = integer("tenant_id") references TenantsTable.id
    val provider = varchar("provider", 32)
    val providerUserId = varchar("provider_user_id", 255)
    val providerEmail = varchar("provider_email", 255).nullable()
    val providerName = varchar("provider_name", 255).nullable()
    val avatarUrl = text("avatar_url").nullable()
    val linkedAt = timestampWithTimeZone("linked_at")

    override val primaryKey = PrimaryKey(id)
}
