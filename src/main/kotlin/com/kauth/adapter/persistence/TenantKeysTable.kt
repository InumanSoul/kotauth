package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed mapping for 'tenant_keys' (V5 migration).
 * Schema is Flyway-owned — no constraint declarations here.
 */
object TenantKeysTable : Table("tenant_keys") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id") references TenantsTable.id
    val keyId = varchar("key_id", 128)
    val algorithm = varchar("algorithm", 10).default("RS256")
    val publicKey = text("public_key")
    val privateKey = text("private_key")
    val enabled = bool("enabled").default(true)
    val active = bool("active").default(false)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
