package com.kauth.adapter.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed mapping for 'sessions' (V6 migration).
 */
object SessionsTable : Table("sessions") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id") references TenantsTable.id
    val userId = integer("user_id").references(UsersTable.id).nullable()
    val clientId = integer("client_id").references(ClientsTable.id).nullable()
    val accessTokenHash = varchar("access_token_hash", 64)
    val refreshTokenHash = varchar("refresh_token_hash", 64).nullable()
    val scopes = text("scopes").default("openid")
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val refreshExpiresAt = timestampWithTimeZone("refresh_expires_at").nullable()
    val lastActivityAt = timestampWithTimeZone("last_activity_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val revocationReason = varchar("revocation_reason", 32).nullable()
    val impersonatorSessionId = integer("impersonator_session_id").references(id).nullable()
    val resources = jsonb("resources").default("[]")

    override val primaryKey = PrimaryKey(id)
}
