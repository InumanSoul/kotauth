package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed mapping for 'authorization_codes' (V7 migration).
 */
object AuthorizationCodesTable : Table("authorization_codes") {
    val id = integer("id").autoIncrement()
    val code = varchar("code", 128)
    val tenantId = integer("tenant_id") references TenantsTable.id
    val clientId = integer("client_id") references ClientsTable.id
    val userId = integer("user_id") references UsersTable.id
    val redirectUri = text("redirect_uri")
    val scopes = text("scopes").default("openid")
    val codeChallenge = varchar("code_challenge", 512).nullable()
    val codeChallengeMethod = varchar("code_challenge_method", 10).nullable()
    val nonce = varchar("nonce", 512).nullable()
    val state = varchar("state", 512).nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
