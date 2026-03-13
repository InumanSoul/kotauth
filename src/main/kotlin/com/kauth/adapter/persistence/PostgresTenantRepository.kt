package com.kauth.adapter.persistence

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TenantRepository
import com.kauth.infrastructure.EncryptionService
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
 * Phase 3b: SMTP password is encrypted/decrypted transparently using [EncryptionService].
 * If [EncryptionService] is unavailable (KAUTH_SECRET_KEY not set), the password field
 * is stored as null and SMTP config will not function.
 */
class PostgresTenantRepository : TenantRepository {

    override fun findBySlug(slug: String): Tenant? = transaction {
        TenantsTable.selectAll()
            .where { TenantsTable.slug eq slug }
            .map { it.toTenant() }
            .singleOrNull()
    }

    override fun existsBySlug(slug: String): Boolean = transaction {
        TenantsTable.selectAll()
            .where { TenantsTable.slug eq slug }
            .count() > 0
    }

    override fun findAll(): List<Tenant> = transaction {
        TenantsTable.selectAll()
            .orderBy(TenantsTable.id)
            .map { it.toTenant() }
    }

    override fun findById(id: Int): Tenant? = transaction {
        TenantsTable.selectAll()
            .where { TenantsTable.id eq id }
            .map { it.toTenant() }
            .singleOrNull()
    }

    override fun update(tenant: Tenant): Tenant = transaction {
        // Encrypt SMTP password before persistence (only if it changed / is set)
        val encryptedPassword: String? = tenant.smtpPassword?.let { raw ->
            if (EncryptionService.isAvailable) EncryptionService.encrypt(raw) else null
        }

        TenantsTable.update({ TenantsTable.id eq tenant.id }) {
            it[displayName]                  = tenant.displayName
            it[issuerUrl]                    = tenant.issuerUrl
            it[tokenExpirySeconds]           = tenant.tokenExpirySeconds.toInt()
            it[refreshTokenExpirySeconds]    = tenant.refreshTokenExpirySeconds.toInt()
            it[registrationEnabled]          = tenant.registrationEnabled
            it[emailVerificationRequired]    = tenant.emailVerificationRequired
            it[passwordPolicyMinLength]      = tenant.passwordPolicyMinLength
            it[passwordPolicyRequireSpecial] = tenant.passwordPolicyRequireSpecial
            it[themeAccentColor]             = tenant.theme.accentColor
            it[themeAccentHover]             = tenant.theme.accentHoverColor
            it[themeLogoUrl]                 = tenant.theme.logoUrl
            it[themeFaviconUrl]              = tenant.theme.faviconUrl
            // Phase 3b SMTP fields
            it[smtpHost]               = tenant.smtpHost
            it[smtpPort]               = tenant.smtpPort
            it[smtpUsername]           = tenant.smtpUsername
            it[smtpPassword]           = encryptedPassword
            it[smtpFromAddress]        = tenant.smtpFromAddress
            it[smtpFromName]           = tenant.smtpFromName
            it[smtpTlsEnabled]         = tenant.smtpTlsEnabled
            it[smtpEnabled]            = tenant.smtpEnabled
            it[maxConcurrentSessions]  = tenant.maxConcurrentSessions
        }
        TenantsTable.selectAll()
            .where { TenantsTable.id eq tenant.id }
            .single()
            .toTenant()
    }

    override fun create(slug: String, displayName: String, issuerUrl: String?): Tenant = transaction {
        val insertedId = TenantsTable.insert {
            it[TenantsTable.slug] = slug
            it[TenantsTable.displayName] = displayName
            it[TenantsTable.issuerUrl] = issuerUrl
        } get TenantsTable.id

        TenantsTable.selectAll()
            .where { TenantsTable.id eq insertedId }
            .single()
            .toTenant()
    }

    private fun ResultRow.toTenant(): Tenant {
        // Decrypt SMTP password on read
        val encryptedPw = this[TenantsTable.smtpPassword]
        val decryptedPw = encryptedPw?.let { EncryptionService.decrypt(it) }

        return Tenant(
            id                           = this[TenantsTable.id],
            slug                         = this[TenantsTable.slug],
            displayName                  = this[TenantsTable.displayName],
            issuerUrl                    = this[TenantsTable.issuerUrl],
            tokenExpirySeconds           = this[TenantsTable.tokenExpirySeconds].toLong(),
            refreshTokenExpirySeconds    = this[TenantsTable.refreshTokenExpirySeconds].toLong(),
            registrationEnabled          = this[TenantsTable.registrationEnabled],
            emailVerificationRequired    = this[TenantsTable.emailVerificationRequired],
            passwordPolicyMinLength      = this[TenantsTable.passwordPolicyMinLength],
            passwordPolicyRequireSpecial = this[TenantsTable.passwordPolicyRequireSpecial],
            theme = TenantTheme(
                accentColor      = this[TenantsTable.themeAccentColor],
                accentHoverColor = this[TenantsTable.themeAccentHover],
                bgDeep           = this[TenantsTable.themeBgDeep],
                bgCard           = this[TenantsTable.themeBgCard],
                bgInput          = this[TenantsTable.themeBgInput],
                borderColor      = this[TenantsTable.themeBorderColor],
                borderRadius     = this[TenantsTable.themeBorderRadius],
                textPrimary      = this[TenantsTable.themeTextPrimary],
                textMuted        = this[TenantsTable.themeTextMuted],
                logoUrl          = this[TenantsTable.themeLogoUrl],
                faviconUrl       = this[TenantsTable.themeFaviconUrl]
            ),
            smtpHost               = this[TenantsTable.smtpHost],
            smtpPort               = this[TenantsTable.smtpPort],
            smtpUsername           = this[TenantsTable.smtpUsername],
            smtpPassword           = decryptedPw,
            smtpFromAddress        = this[TenantsTable.smtpFromAddress],
            smtpFromName           = this[TenantsTable.smtpFromName],
            smtpTlsEnabled         = this[TenantsTable.smtpTlsEnabled],
            smtpEnabled            = this[TenantsTable.smtpEnabled],
            maxConcurrentSessions  = this[TenantsTable.maxConcurrentSessions]
        )
    }
}
