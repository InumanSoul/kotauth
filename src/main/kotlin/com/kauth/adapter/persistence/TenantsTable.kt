package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed ORM mapping for the 'tenants' table.
 * Schema is Flyway-owned — no uniqueIndex() or constraint declarations here.
 *
 * Security policy (password, MFA, lockout) lives in tenant_security_config (V26).
 * Theme columns live in workspace_theme (V23).
 * Portal config lives in workspace_portal_config (V22).
 */
object TenantsTable : Table("tenants") {
    val id = integer("id").autoIncrement()
    val slug = varchar("slug", 50)
    val displayName = varchar("display_name", 100)
    val issuerUrl = varchar("issuer_url", 255).nullable()
    val tokenExpirySeconds = integer("token_expiry_seconds").default(3600)
    val refreshTokenExpirySeconds = integer("refresh_token_expiry_seconds").default(86400)
    val registrationEnabled = bool("registration_enabled").default(true)
    val emailVerificationRequired = bool("email_verification_required").default(false)

    // SMTP columns
    // smtp_password stores AES-256-GCM encrypted ciphertext (see EncryptionService)
    val smtpHost = varchar("smtp_host", 255).nullable()
    val smtpPort = integer("smtp_port").default(587)
    val smtpUsername = varchar("smtp_username", 255).nullable()
    val smtpPassword = text("smtp_password").nullable()
    val smtpFromAddress = varchar("smtp_from_address", 255).nullable()
    val smtpFromName = varchar("smtp_from_name", 255).nullable()
    val smtpTlsEnabled = bool("smtp_tls_enabled").default(true)
    val smtpEnabled = bool("smtp_enabled").default(false)
    val maxConcurrentSessions = integer("max_concurrent_sessions").nullable()

    override val primaryKey = PrimaryKey(id)
}
