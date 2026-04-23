package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed mapping for 'tenant_claim_mappers' (V34 migration).
 * Schema is Flyway-owned — no constraint declarations here.
 */
object TenantClaimMappersTable : Table("tenant_claim_mappers") {
    val tenantId = integer("tenant_id")
    val attributeKey = varchar("attribute_key", 64)
    val claimName = varchar("claim_name", 128)
    val includeInAccess = bool("include_in_access").default(true)
    val includeInId = bool("include_in_id").default(false)

    override val primaryKey = PrimaryKey(tenantId, attributeKey)
}
