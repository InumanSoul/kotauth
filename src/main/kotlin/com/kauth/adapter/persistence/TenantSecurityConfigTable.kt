package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'tenant_security_config' table.
 *
 * Stores password policy, MFA policy, and account lockout settings for each tenant.
 * This table has a 1:1 relationship with tenants and is loaded via LEFT JOIN in
 * [PostgresTenantRepository], composed into [com.kauth.domain.model.Tenant.securityConfig].
 */
object TenantSecurityConfigTable : Table("tenant_security_config") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id").references(TenantsTable.id)
    val passwordMinLength = integer("password_min_length").default(8)
    val passwordRequireSpecial = bool("password_require_special").default(false)
    val passwordRequireUppercase = bool("password_require_uppercase").default(false)
    val passwordRequireNumber = bool("password_require_number").default(false)
    val passwordHistoryCount = integer("password_history_count").default(0)
    val passwordMaxAgeDays = integer("password_max_age_days").default(0)
    val passwordBlacklistEnabled = bool("password_blacklist_enabled").default(false)
    val mfaPolicy = varchar("mfa_policy", 30).default("optional")
    val lockoutMaxAttempts = integer("lockout_max_attempts").default(0)
    val lockoutDurationMinutes = integer("lockout_duration_minutes").default(15)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
