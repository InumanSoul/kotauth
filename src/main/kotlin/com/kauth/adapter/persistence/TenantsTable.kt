package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed ORM mapping for the 'tenants' table (V1 + V3 + V9 migrations).
 * Schema is Flyway-owned — no uniqueIndex() or constraint declarations here.
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
    val passwordPolicyMinLength = integer("password_policy_min_length").default(8)
    val passwordPolicyRequireSpecial = bool("password_policy_require_special").default(false)

    val passwordPolicyHistoryCount = integer("password_policy_history_count").default(0)
    val passwordPolicyMaxAgeDays = integer("password_policy_max_age_days").default(0)
    val passwordPolicyRequireUppercase = bool("password_policy_require_uppercase").default(false)
    val passwordPolicyRequireNumber = bool("password_policy_require_number").default(false)
    val passwordPolicyBlacklistEnabled = bool("password_policy_blacklist_enabled").default(false)

    // Theme columns — added by V3 migration, defaults match TenantTheme.DEFAULT
    // Zinc-dark palette with brand cyan accent (#1FBCFF) — updated from legacy purple
    val themeAccentColor = varchar("theme_accent_color", 30).default("#1FBCFF")
    val themeAccentHover = varchar("theme_accent_hover", 30).default("#0ea5d9")
    val themeBgDeep = varchar("theme_bg_deep", 30).default("#09090b")
    val themeBgCard = varchar("theme_bg_card", 30).default("#18181b")
    val themeBgInput = varchar("theme_bg_input", 30).default("#27272a")
    val themeBorderColor = varchar("theme_border_color", 30).default("#3f3f46")
    val themeBorderRadius = varchar("theme_border_radius", 20).default("8px")
    val themeTextPrimary = varchar("theme_text_primary", 30).default("#fafafa")
    val themeTextMuted = varchar("theme_text_muted", 30).default("#a1a1aa")
    val themeLogoUrl = varchar("theme_logo_url", 500).nullable()
    val themeFaviconUrl = varchar("theme_favicon_url", 500).nullable()

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

    // MFA policy — 'optional', 'required', 'required_admins'
    val mfaPolicy = varchar("mfa_policy", 20).default("optional")

    override val primaryKey = PrimaryKey(id)
}
