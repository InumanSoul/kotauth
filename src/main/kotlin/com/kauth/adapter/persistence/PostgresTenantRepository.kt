package com.kauth.adapter.persistence

import com.kauth.domain.model.PortalConfig
import com.kauth.domain.model.PortalLayout
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
 * Tenant lookups are frequent (every auth request) and read-heavy.
 * A simple in-process cache keyed by slug would be a worthwhile optimisation
 * once traffic warrants it — the port interface makes that swap transparent.
 *
 * SMTP password is encrypted/decrypted transparently using [EncryptionPort].
 * If encryption is unavailable (KAUTH_SECRET_KEY not set), the password field
 * is stored as null and SMTP config will not function.
 */
class PostgresTenantRepository(
    private val encryptionService: EncryptionPort,
) : TenantRepository {
    private val tenantWithPortalConfig =
        TenantsTable.join(
            WorkspacePortalConfigTable,
            JoinType.LEFT,
            onColumn = TenantsTable.id,
            otherColumn = WorkspacePortalConfigTable.tenantId,
        )

    override fun findBySlug(slug: String): Tenant? =
        transaction {
            tenantWithPortalConfig
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
            tenantWithPortalConfig
                .selectAll()
                .orderBy(TenantsTable.id)
                .map { it.toTenant() }
        }

    override fun findById(id: TenantId): Tenant? =
        transaction {
            tenantWithPortalConfig
                .selectAll()
                .where { TenantsTable.id eq id.value }
                .map { it.toTenant() }
                .singleOrNull()
        }

    override fun update(tenant: Tenant): Tenant =
        transaction {
            // Encrypt SMTP password before persistence (only if it changed / is set)
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
                it[passwordPolicyMinLength] = tenant.passwordPolicyMinLength
                it[passwordPolicyRequireSpecial] = tenant.passwordPolicyRequireSpecial
                it[themeAccentColor] = tenant.theme.accentColor
                it[themeAccentHover] = tenant.theme.accentHoverColor
                it[themeBgDeep] = tenant.theme.bgDeep
                it[themeBgCard] = tenant.theme.bgCard
                it[themeBgInput] = tenant.theme.bgInput
                it[themeBorderColor] = tenant.theme.borderColor
                it[themeBorderRadius] = tenant.theme.borderRadius
                it[themeTextPrimary] = tenant.theme.textPrimary
                it[themeTextMuted] = tenant.theme.textMuted
                it[themeLogoUrl] = tenant.theme.logoUrl
                it[themeFaviconUrl] = tenant.theme.faviconUrl
                it[smtpHost] = tenant.smtpHost
                it[smtpPort] = tenant.smtpPort
                it[smtpUsername] = tenant.smtpUsername
                it[smtpPassword] = encryptedPassword
                it[smtpFromAddress] = tenant.smtpFromAddress
                it[smtpFromName] = tenant.smtpFromName
                it[smtpTlsEnabled] = tenant.smtpTlsEnabled
                it[smtpEnabled] = tenant.smtpEnabled
                it[maxConcurrentSessions] = tenant.maxConcurrentSessions
                it[passwordPolicyHistoryCount] = tenant.passwordPolicyHistoryCount
                it[passwordPolicyMaxAgeDays] = tenant.passwordPolicyMaxAgeDays
                it[passwordPolicyRequireUppercase] = tenant.passwordPolicyRequireUppercase
                it[passwordPolicyRequireNumber] = tenant.passwordPolicyRequireNumber
                it[passwordPolicyBlacklistEnabled] = tenant.passwordPolicyBlacklistEnabled
                it[mfaPolicy] = tenant.mfaPolicy
            }
            tenantWithPortalConfig
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

            tenantWithPortalConfig
                .selectAll()
                .where { TenantsTable.id eq insertedId }
                .single()
                .toTenant()
        }

    private fun ResultRow.toTenant(): Tenant {
        // Decrypt SMTP password on read
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
            passwordPolicyMinLength = this[TenantsTable.passwordPolicyMinLength],
            passwordPolicyRequireSpecial = this[TenantsTable.passwordPolicyRequireSpecial],
            passwordPolicyHistoryCount = this[TenantsTable.passwordPolicyHistoryCount],
            passwordPolicyMaxAgeDays = this[TenantsTable.passwordPolicyMaxAgeDays],
            passwordPolicyRequireUppercase = this[TenantsTable.passwordPolicyRequireUppercase],
            passwordPolicyRequireNumber = this[TenantsTable.passwordPolicyRequireNumber],
            passwordPolicyBlacklistEnabled = this[TenantsTable.passwordPolicyBlacklistEnabled],
            theme =
                TenantTheme(
                    accentColor = this[TenantsTable.themeAccentColor],
                    accentHoverColor = this[TenantsTable.themeAccentHover],
                    bgDeep = this[TenantsTable.themeBgDeep],
                    bgCard = this[TenantsTable.themeBgCard],
                    bgInput = this[TenantsTable.themeBgInput],
                    borderColor = this[TenantsTable.themeBorderColor],
                    borderRadius = this[TenantsTable.themeBorderRadius],
                    textPrimary = this[TenantsTable.themeTextPrimary],
                    textMuted = this[TenantsTable.themeTextMuted],
                    logoUrl = this[TenantsTable.themeLogoUrl],
                    faviconUrl = this[TenantsTable.themeFaviconUrl],
                ),
            smtpHost = this[TenantsTable.smtpHost],
            smtpPort = this[TenantsTable.smtpPort],
            smtpUsername = this[TenantsTable.smtpUsername],
            smtpPassword = decryptedPw,
            smtpFromAddress = this[TenantsTable.smtpFromAddress],
            smtpFromName = this[TenantsTable.smtpFromName],
            smtpTlsEnabled = this[TenantsTable.smtpTlsEnabled],
            smtpEnabled = this[TenantsTable.smtpEnabled],
            mfaPolicy = this[TenantsTable.mfaPolicy],
            maxConcurrentSessions = this[TenantsTable.maxConcurrentSessions],
            portalConfig = toPortalConfig(),
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
