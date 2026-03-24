package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed mapping for 'audit_log' (V8 migration).
 * The [details] column uses [JsonbColumnType] to send values as PGobject(type="jsonb"),
 * matching the JSONB column type in PostgreSQL.
 */
object AuditLogTable : Table("audit_log") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id").references(TenantsTable.id).nullable()
    val userId = integer("user_id").references(UsersTable.id).nullable()
    val clientId = integer("client_id").references(ClientsTable.id).nullable()
    val eventType = varchar("event_type", 64)
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val details = jsonb("details").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
