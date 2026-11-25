package com.kauth.adapter.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*

/** What a user allowed an application (V74). One row per user and application. */
object UserConsentsTable : Table("user_consents") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id") references TenantsTable.id
    val userId = integer("user_id") references UsersTable.id
    val clientId = integer("client_id") references ClientsTable.id
    val scopes = jsonb("scopes").default("[]")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
