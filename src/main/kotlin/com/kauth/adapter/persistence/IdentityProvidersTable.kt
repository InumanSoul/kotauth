package com.kauth.adapter.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed ORM mapping for the 'identity_providers' table (V17, extended by V63).
 */
object IdentityProvidersTable : Table("identity_providers") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id") references TenantsTable.id
    val provider = varchar("provider", 32)
    val clientId = varchar("client_id", 255)
    val clientSecret = text("client_secret") // AES-256-GCM encrypted
    val enabled = bool("enabled").default(true)
    val kind = varchar("kind", 16).default("oauth2")
    val displayName = varchar("display_name", 64).nullable()
    val issuer = varchar("issuer", 255).nullable()
    val authorizationEndpoint = varchar("authorization_endpoint", 512).nullable()
    val tokenEndpoint = varchar("token_endpoint", 512).nullable()
    val jwksUri = varchar("jwks_uri", 512).nullable()
    val scopes = varchar("scopes", 255).default("openid email profile")
    val jitEnabled = bool("jit_enabled").default(false)
    val jitAllowedDomains = text("jit_allowed_domains").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
