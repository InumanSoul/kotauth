package com.kauth.adapter.persistence

import com.kauth.domain.model.PortalConfig
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.EncryptionPort
import com.kauth.domain.port.TenantRepository
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Persistence adapter — implements TenantRepository using PostgreSQL + Exposed.
 *
 * Reads compose the full [Tenant] aggregate via LEFT JOINs to workspace_theme
 * and workspace_portal_config. Theme and portal config are separate tables but
 * exposed as composed value objects on [Tenant] to keep all existing call sites
 * working without changes.
 *
 * Writes only touch the tenants table — theme updates go through [ThemeRepository]
 * and portal config through [PortalConfigRepository].
 *
 * SMTP password is encrypted/decrypted transparently using [EncryptionPort].
 */
class PostgresTenantRepository(
    private val encryptionService: EncryptionPort,
) : TenantRepository {
    private val tenantJoined =
        TenantsTable
            .join(
                WorkspaceThemeTable,
                JoinType.LEFT,
                onColumn = TenantsTable.id,
                otherColumn = WorkspaceThemeTable.tenantId,
            ).join(
                WorkspacePortalConfigTable,
                JoinType.LEFT,
                onColumn = TenantsTable.id,
                otherColumn = WorkspacePortalConfigTable.tenantId,
            ).join(
                TenantSecurityConfigTable,
                JoinType.LEFT,
                onColumn = TenantsTable.id,
                otherColumn = TenantSecurityConfigTable.tenantId,
            )

    override fun findBySlug(slug: String): Tenant? =
        transaction {
            tenantJoined
                .selectAll()
                .where { TenantsTable.slug eq slug }
                .map { it.toTenant() }
                .singleOrNull()
        }

    override fun existsBySlug(slug: String): Boolean =
        transaction {
            TenantsTable
                .selectAll()
                .where { TenantsTable.slug eq slug }
                .count() > 0
        }

    override fun findAll(): List<Tenant> =
        transaction {
            tenantJoined
                .selectAll()
                .orderBy(TenantsTable.id)
                .map { it.toTenant() }
        }

    override fun findById(id: TenantId): Tenant? =
        transaction {
            tenantJoined
                .selectAll()
                .where { TenantsTable.id eq id.value }
                .map { it.toTenant() }
                .singleOrNull()
        }

    override fun update(tenant: Tenant): Tenant =
        transaction {
            val encryptedPassword: String? =
                tenant.smtpPassword?.let { raw ->
                    if (encryptionService.isAvailable) encryptionService.encrypt(raw) else null
                }

            TenantsTable.update({ TenantsTable.id eq tenant.id.value }) {
                it[displayName] = tenant.displayName
                it[issuerUrl] = tenant.issuerUrl
                it[tokenExpirySeconds] = tenant.tokenExpirySeconds.toInt()
                it[refreshTokenExpirySeconds] = tenant.refreshTokenExpirySeconds.toInt()
                it[registrationEnabled] = tenant.registrationEnabled
                it[emailVerificationRequired] = tenant.emailVerificationRequired
                it[smtpHost] = tenant.smtpHost
                it[smtpPort] = tenant.smtpPort
                it[smtpUsername] = tenant.smtpUsername
                it[smtpPassword] = encryptedPassword
                it[smtpFromAddress] = tenant.smtpFromAddress
                it[smtpFromName] = tenant.smtpFromName
                it[smtpTlsEnabled] = tenant.smtpTlsEnabled
                it[smtpEnabled] = tenant.smtpEnabled
                it[maxConcurrentSessions] = tenant.maxConcurrentSessions
            }
            TenantSecurityConfigTable.update({ TenantSecurityConfigTable.tenantId eq tenant.id.value }) {
                it[passwordMinLength] = tenant.securityConfig.passwordMinLength
                it[passwordRequireSpecial] = tenant.securityConfig.passwordRequireSpecial
                it[passwordRequireUppercase] = tenant.securityConfig.passwordRequireUppercase
                it[passwordRequireNumber] = tenant.securityConfig.passwordRequireNumber
                it[passwordHistoryCount] = tenant.securityConfig.passwordHistoryCount
                it[passwordMaxAgeDays] = tenant.securityConfig.passwordMaxAgeDays
                it[passwordBlacklistEnabled] = tenant.securityConfig.passwordBlacklistEnabled
                it[mfaPolicy] = tenant.securityConfig.mfaPolicy
                it[lockoutMaxAttempts] = tenant.securityConfig.lockoutMaxAttempts
                it[lockoutDurationMinutes] = tenant.securityConfig.lockoutDurationMinutes
            }
            tenantJoined
                .selectAll()
                .where { TenantsTable.id eq tenant.id.value }
                .single()
                .toTenant()
        }

    override fun create(
        slug: String,
        displayName: String,
        issuerUrl: String?,
    ): Tenant =
        transaction {
            val insertedId =
                TenantsTable.insert {
                    it[TenantsTable.slug] = slug
                    it[TenantsTable.displayName] = displayName
                    it[TenantsTable.issuerUrl] = issuerUrl
                } get TenantsTable.id

            tenantJoined
                .selectAll()
                .where { TenantsTable.id eq insertedId }
                .single()
                .toTenant()
        }

    private fun ResultRow.toTenant(): Tenant {
        val encryptedPw = this[TenantsTable.smtpPassword]
        val decryptedPw = encryptedPw?.let { encryptionService.decrypt(it) }

        return Tenant(
            id = TenantId(this[TenantsTable.id]),
            slug = this[TenantsTable.slug],
            displayName = this[TenantsTable.displayName],
            issuerUrl = this[TenantsTable.issuerUrl],
            tokenExpirySeconds = this[TenantsTable.tokenExpirySeconds].toLong(),
            refreshTokenExpirySeconds = this[TenantsTable.refreshTokenExpirySeconds].toLong(),
            registrationEnabled = this[TenantsTable.registrationEnabled],
            emailVerificationRequired = this[TenantsTable.emailVerificationRequired],
            securityConfig = toSecurityConfig(),
            theme = toTheme(),
            smtpHost = this[TenantsTable.smtpHost],
            smtpPort = this[TenantsTable.smtpPort],
            smtpUsername = this[TenantsTable.smtpUsername],
            smtpPassword = decryptedPw,
            smtpFromAddress = this[TenantsTable.smtpFromAddress],
            smtpFromName = this[TenantsTable.smtpFromName],
            smtpTlsEnabled = this[TenantsTable.smtpTlsEnabled],
            smtpEnabled = this[TenantsTable.smtpEnabled],
            maxConcurrentSessions = this[TenantsTable.maxConcurrentSessions],
            portalConfig = toPortalConfig(),
        )
    }

    private fun ResultRow.toSecurityConfig(): SecurityConfig {
        // When there is no row in tenant_security_config yet (LEFT JOIN returns null),
        // fall back to the defaults defined on SecurityConfig.
        getOrNull(TenantSecurityConfigTable.lockoutMaxAttempts) ?: return SecurityConfig()
        return SecurityConfig(
            passwordMinLength = this[TenantSecurityConfigTable.passwordMinLength],
            passwordRequireSpecial = this[TenantSecurityConfigTable.passwordRequireSpecial],
            passwordRequireUppercase = this[TenantSecurityConfigTable.passwordRequireUppercase],
            passwordRequireNumber = this[TenantSecurityConfigTable.passwordRequireNumber],
            passwordHistoryCount = this[TenantSecurityConfigTable.passwordHistoryCount],
            passwordMaxAgeDays = this[TenantSecurityConfigTable.passwordMaxAgeDays],
            passwordBlacklistEnabled = this[TenantSecurityConfigTable.passwordBlacklistEnabled],
            mfaPolicy = this[TenantSecurityConfigTable.mfaPolicy],
            lockoutMaxAttempts = this[TenantSecurityConfigTable.lockoutMaxAttempts],
            lockoutDurationMinutes = this[TenantSecurityConfigTable.lockoutDurationMinutes],
        )
    }

    private fun ResultRow.toTheme(): TenantTheme {
        val accent = getOrNull(WorkspaceThemeTable.accentColor) ?: return TenantTheme.DEFAULT
        return TenantTheme(
            accentColor = accent,
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

    private fun ResultRow.toPortalConfig(): PortalConfig {
        val layoutStr = getOrNull(WorkspacePortalConfigTable.layout) ?: return PortalConfig()
        return PortalConfig(
            layout =
                runCatching {
                    PortalLayout.valueOf(layoutStr)
                }.getOrDefault(PortalLayout.SIDEBAR),
        )
    }
}
