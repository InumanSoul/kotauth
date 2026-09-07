package com.kauth.adapter.persistence

import com.kauth.domain.model.LoginLayout
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.ThemeRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
                    it[defaultLocale] = theme.defaultLocale
                    it[loginLayout] = theme.loginLayout.name
                    it[loginBackgroundUrl] = theme.loginBackgroundUrl
                    it[loginTagline] = theme.loginTagline
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
                    it[defaultLocale] = theme.defaultLocale
                    it[loginLayout] = theme.loginLayout.name
                    it[loginBackgroundUrl] = theme.loginBackgroundUrl
                    it[loginTagline] = theme.loginTagline
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
            defaultLocale = this[WorkspaceThemeTable.defaultLocale],
            loginLayout =
                runCatching { LoginLayout.valueOf(this[WorkspaceThemeTable.loginLayout]) }
                    .getOrDefault(LoginLayout.CENTERED),
            loginBackgroundUrl = this[WorkspaceThemeTable.loginBackgroundUrl],
            loginTagline = this[WorkspaceThemeTable.loginTagline],
        )
}
