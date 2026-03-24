package com.kauth.adapter.persistence

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.ThemeRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

/**
 * Persistence adapter — implements [ThemeRepository] using PostgreSQL + Exposed.
 */
class PostgresThemeRepository : ThemeRepository {
    override fun findByTenantId(tenantId: TenantId): TenantTheme? =
        transaction {
            WorkspaceThemeTable
                .selectAll()
                .where { WorkspaceThemeTable.tenantId eq tenantId.value }
                .map { it.toTheme() }
                .singleOrNull()
        }

    override fun upsert(
        tenantId: TenantId,
        theme: TenantTheme,
    ): TenantTheme =
        transaction {
            val exists =
                WorkspaceThemeTable
                    .selectAll()
                    .where { WorkspaceThemeTable.tenantId eq tenantId.value }
                    .count() > 0

            if (exists) {
                WorkspaceThemeTable.update(
                    { WorkspaceThemeTable.tenantId eq tenantId.value },
                ) {
                    it[accentColor] = theme.accentColor
                    it[accentHover] = theme.accentHoverColor
                    it[accentForeground] = theme.accentForeground
                    it[bgDeep] = theme.bgDeep
                    it[surface] = theme.surface
                    it[fontFamily] = theme.fontFamily
                    it[bgInput] = theme.bgInput
                    it[borderColor] = theme.borderColor
                    it[borderRadius] = theme.borderRadius
                    it[textPrimary] = theme.textPrimary
                    it[textMuted] = theme.textMuted
                    it[logoUrl] = theme.logoUrl
                    it[faviconUrl] = theme.faviconUrl
                    it[updatedAt] = OffsetDateTime.now()
                }
            } else {
                WorkspaceThemeTable.insert {
                    it[WorkspaceThemeTable.tenantId] = tenantId.value
                    it[accentColor] = theme.accentColor
                    it[accentHover] = theme.accentHoverColor
                    it[accentForeground] = theme.accentForeground
                    it[bgDeep] = theme.bgDeep
                    it[surface] = theme.surface
                    it[fontFamily] = theme.fontFamily
                    it[bgInput] = theme.bgInput
                    it[borderColor] = theme.borderColor
                    it[borderRadius] = theme.borderRadius
                    it[textPrimary] = theme.textPrimary
                    it[textMuted] = theme.textMuted
                    it[logoUrl] = theme.logoUrl
                    it[faviconUrl] = theme.faviconUrl
                    it[createdAt] = OffsetDateTime.now()
                    it[updatedAt] = OffsetDateTime.now()
                }
            }
            theme
        }

    private fun ResultRow.toTheme(): TenantTheme =
        TenantTheme(
            accentColor = this[WorkspaceThemeTable.accentColor],
            accentHoverColor = this[WorkspaceThemeTable.accentHover],
            accentForeground = this[WorkspaceThemeTable.accentForeground],
            bgDeep = this[WorkspaceThemeTable.bgDeep],
            surface = this[WorkspaceThemeTable.surface],
            fontFamily = this[WorkspaceThemeTable.fontFamily],
            bgInput = this[WorkspaceThemeTable.bgInput],
            borderColor = this[WorkspaceThemeTable.borderColor],
            borderRadius = this[WorkspaceThemeTable.borderRadius],
            textPrimary = this[WorkspaceThemeTable.textPrimary],
            textMuted = this[WorkspaceThemeTable.textMuted],
            logoUrl = this[WorkspaceThemeTable.logoUrl],
            faviconUrl = this[WorkspaceThemeTable.faviconUrl],
        )
}
