package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed ORM mapping for the 'clients' and 'client_redirect_uris' tables
 * created by V2 migration.
 *
 * Full client CRUD will be wired up in the admin console (Phase 1).
 * Defined here so the schema is represented in code alongside the SQL.
 */
object ClientsTable : Table("clients") {
    val id                 = integer("id").autoIncrement()
    val tenantId           = integer("tenant_id") references TenantsTable.id
    val clientId           = varchar("client_id", 100)
    val clientSecretHash   = varchar("client_secret_hash", 128).nullable()
    val name               = varchar("name", 100)
    val description        = text("description").nullable()
    val accessType         = varchar("access_type", 20).default("public")
    val tokenExpiryOverride = integer("token_expiry_override").nullable()
    val enabled            = bool("enabled").default(true)

    override val primaryKey = PrimaryKey(id)
}

object ClientRedirectUrisTable : Table("client_redirect_uris") {
    val id       = integer("id").autoIncrement()
    val clientId = integer("client_id") references ClientsTable.id
    val uri      = varchar("uri", 500)

    override val primaryKey = PrimaryKey(id)
}
