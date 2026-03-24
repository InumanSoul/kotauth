package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'workspace_portal_config' table (V22 migration).
 */
object WorkspacePortalConfigTable : Table("workspace_portal_config") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id").references(TenantsTable.id)
    val layout = varchar("layout", 20).default("SIDEBAR")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
