package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'workspace_theme' table (V23 migration).
 * Stores per-tenant visual identity — extracted from the former theme_* columns on tenants.
 */
object WorkspaceThemeTable : Table("workspace_theme") {
    val id = integer("id").autoIncrement()
    val tenantId = integer("tenant_id").references(TenantsTable.id)
    val accentColor = varchar("accent_color", 30).default("#1FBCFF")
    val accentHover = varchar("accent_hover", 30).default("#0ea5d9")
    val accentForeground = varchar("accent_foreground", 30).default("#05080a")
    val bgDeep = varchar("bg_deep", 30).default("#09090b")
    val surface = varchar("surface", 30).default("#18181b")
    val fontFamily = varchar("font_family", 60).default("Inter")
    val bgInput = varchar("bg_input", 30).default("#27272a")
    val borderColor = varchar("border_color", 30).default("#3f3f46")
    val borderRadius = varchar("border_radius", 20).default("8px")
    val textPrimary = varchar("text_primary", 30).default("#fafafa")
    val textMuted = varchar("text_muted", 30).default("#a1a1aa")
    val logoUrl = varchar("logo_url", 500).nullable()
    val faviconUrl = varchar("favicon_url", 500).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
