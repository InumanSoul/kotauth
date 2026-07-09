package com.kauth.adapter.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*

object WebAuthnCredentialsTable : Table("webauthn_credentials") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id)
    val tenantId = integer("tenant_id").references(TenantsTable.id)
    val credentialId = text("credential_id").uniqueIndex()
    val publicKeyCose = binary("public_key_cose")
    val signCounter = long("sign_counter").default(0)
    val aaguid = varchar("aaguid", 36).nullable()
    val transports = jsonb("transports").default("[]")
    val name = varchar("name", 64)
    val backupEligible = bool("backup_eligible").default(false)
    val backupState = bool("backup_state").default(false)
    val createdAt = timestampWithTimeZone("created_at")
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
