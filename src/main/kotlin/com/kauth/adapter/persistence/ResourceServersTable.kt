package com.kauth.adapter.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*

object ResourceServersTable : Table("resource_servers") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id") references TenantsTable.id
    val identifier = varchar("identifier", 255)
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val enabled = bool("enabled").default(true)
    val scopes = jsonb("scopes").default("[]")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object ClientAuthorizedResourcesTable : Table("client_authorized_resources") {
    val clientId = integer("client_id") references ClientsTable.id
    val resourceServerId = integer("resource_server_id") references ResourceServersTable.id

    override val primaryKey = PrimaryKey(clientId, resourceServerId)
}
