package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed mapping for 'audit_log' (V8 migration).
 * JSONB is read/written as text — Exposed doesn't have native JSONB support
 * without an extension. Text serialization is safe and forward-compatible.
 */
object AuditLogTable : Table("audit_log") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id").references(TenantsTable.id).nullable()
    val userId = integer("user_id").references(UsersTable.id).nullable()
    val clientId = integer("client_id").references(ClientsTable.id).nullable()
    val eventType = varchar("event_type", 64)
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val details = text("details").nullable() // JSONB stored as text
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
