package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'identity_providers' table (V17 migration).
 */
object IdentityProvidersTable : Table("identity_providers") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id") references TenantsTable.id
    val provider = varchar("provider", 32)
    val clientId = varchar("client_id", 255)
    val clientSecret = text("client_secret") // AES-256-GCM encrypted
    val enabled = bool("enabled").default(true)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
