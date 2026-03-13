package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed ORM mapping for the 'tenants' table (V1 + V3 migrations).
 * Schema is Flyway-owned — no uniqueIndex() or constraint declarations here.
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

    // Theme columns — added by V3 migration, defaults match TenantTheme.DEFAULT
    // Zinc-dark palette with brand cyan accent (#1FBCFF) — updated from legacy purple
    val themeAccentColor  = varchar("theme_accent_color",  30).default("#1FBCFF")
    val themeAccentHover  = varchar("theme_accent_hover",  30).default("#0ea5d9")
    val themeBgDeep       = varchar("theme_bg_deep",       30).default("#09090b")
    val themeBgCard       = varchar("theme_bg_card",       30).default("#18181b")
    val themeBgInput      = varchar("theme_bg_input",      30).default("#27272a")
    val themeBorderColor  = varchar("theme_border_color",  30).default("#3f3f46")
    val themeBorderRadius = varchar("theme_border_radius", 20).default("8px")
    val themeTextPrimary  = varchar("theme_text_primary",  30).default("#fafafa")
    val themeTextMuted    = varchar("theme_text_muted",    30).default("#a1a1aa")
    val themeLogoUrl      = varchar("theme_logo_url",     500).nullable()
    val themeFaviconUrl   = varchar("theme_favicon_url",  500).nullable()

    override val primaryKey = PrimaryKey(id)
}
