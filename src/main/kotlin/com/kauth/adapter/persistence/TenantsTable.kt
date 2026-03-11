package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed ORM mapping for the 'tenants' table created by V1 migration.
 * Read-only from the application's perspective at this stage —
 * tenant creation will be handled via the admin console in Phase 1.
 */
object TenantsTable : Table("tenants") {
    val id                           = integer("id").autoIncrement()
    val slug                         = varchar("slug", 50)
    val displayName                  = varchar("display_name", 100)
    val issuerUrl                    = varchar("issuer_url", 255).nullable()
    val tokenExpirySeconds           = integer("token_expiry_seconds").default(3600)
    val refreshTokenExpirySeconds    = integer("refresh_token_expiry_seconds").default(86400)
    val registrationEnabled          = bool("registration_enabled").default(true)
    val emailVerificationRequired    = bool("email_verification_required").default(false)
    val passwordPolicyMinLength      = integer("password_policy_min_length").default(8)
    val passwordPolicyRequireSpecial = bool("password_policy_require_special").default(false)

    override val primaryKey = PrimaryKey(id)
}
