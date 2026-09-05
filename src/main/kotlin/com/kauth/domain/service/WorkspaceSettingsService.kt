package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.PortalConfig
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantEmailBranding
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.WorkspaceSettingsUpdate
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.CorsPort
import com.kauth.domain.port.PortalConfigRepository
import com.kauth.domain.port.TenantEmailBrandingRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.ThemeRepository
import com.kauth.domain.util.validateTenantTheme

class WorkspaceSettingsService(
    private val tenantRepository: TenantRepository,
    private val auditLog: AuditLogPort,
    private val themeRepository: ThemeRepository? = null,
    private val portalConfigRepository: PortalConfigRepository? = null,
    private val emailBrandingRepository: TenantEmailBrandingRepository? = null,
    private val corsPort: CorsPort? = null,
) {
    fun createWorkspace(
        slug: String,
        displayName: String,
        issuerUrl: String?,
    ): AdminResult<Tenant> {
        if (slug.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Slug is required."))
        }
        if (!slug.matches(Regex("[a-z0-9-]+"))) {
            return AdminResult.Failure(
                AdminError.Validation("Slug may only contain lowercase letters, numbers, and hyphens."),
            )
        }
        if (slug == Tenant.MASTER_SLUG) {
            return AdminResult.Failure(AdminError.Validation("The slug 'master' is reserved."))
        }
        if (displayName.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Display name is required."))
        }
        if (tenantRepository.existsBySlug(slug)) {
            return AdminResult.Failure(AdminError.Validation("A workspace with slug '$slug' already exists."))
        }
        val tenant = tenantRepository.create(slug, displayName, issuerUrl)
        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_TENANT_CREATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("slug" to slug, "displayName" to displayName),
            ),
        )
        return AdminResult.Success(tenant)
    }

    fun updateWorkspaceSettings(
        slug: String,
        update: WorkspaceSettingsUpdate,
    ): AdminResult<Tenant> {
        val tenant =
            tenantRepository.findBySlug(slug)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace '$slug' not found."))

        validateWorkspaceSettings(update, tenant)?.let { return AdminResult.Failure(it) }

        val updated = applyWorkspaceSettings(tenant, update)
        val saved = tenantRepository.update(updated)

        corsPort?.invalidate(slug)
        recordWorkspaceSettingsAudit(tenant, update)

        return AdminResult.Success(saved)
    }

    fun updateTheme(
        slug: String,
        theme: TenantTheme,
    ): AdminResult<TenantTheme> {
        val repo =
            themeRepository
                ?: return AdminResult.Failure(AdminError.Validation("Theme repository not configured."))
        val tenant =
            tenantRepository.findBySlug(slug)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace '$slug' not found."))

        val sanitized = sanitizeTheme(theme)
        validateTenantTheme(sanitized)?.let { return AdminResult.Failure(AdminError.Validation(it)) }
        val saved = repo.upsert(tenant.id, sanitized)

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_TENANT_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("slug" to slug, "action" to "theme_updated"),
            ),
        )

        return AdminResult.Success(saved)
    }

    fun updateEmailBranding(
        slug: String,
        branding: TenantEmailBranding,
    ): AdminResult<TenantEmailBranding> {
        val repo = emailBrandingRepository ?: return AdminResult.Success(branding)
        val tenant =
            tenantRepository.findBySlug(slug)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace '$slug' not found."))

        val supportEmail = branding.supportEmail?.trim()?.takeIf { it.isNotBlank() }
        if (supportEmail != null && "@" !in supportEmail) {
            return AdminResult.Failure(AdminError.Validation("Support email must be a valid address."))
        }
        val color = branding.brandColorHex?.trim()?.takeIf { it.isNotBlank() }
        if (color != null && !color.matches(Regex("^#[0-9a-fA-F]{3,6}$"))) {
            return AdminResult.Failure(AdminError.Validation("Brand color must be a hex value like #1FBCFF."))
        }

        val sanitized =
            TenantEmailBranding(
                tenantId = tenant.id,
                brandName = branding.brandName?.trim()?.takeIf { it.isNotBlank() },
                brandColorHex = color,
                brandLogoUrl = branding.brandLogoUrl?.trim()?.takeIf { it.isNotBlank() },
                supportEmail = supportEmail,
                fromDisplayName = branding.fromDisplayName?.trim()?.takeIf { it.isNotBlank() },
            )

        val saved = repo.upsert(sanitized)
        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_TENANT_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("slug" to slug, "action" to "email_branding_updated"),
            ),
        )
        return AdminResult.Success(saved)
    }

    fun updatePortalLayout(
        slug: String,
        layout: PortalLayout,
    ): AdminResult<PortalConfig> {
        val repo =
            portalConfigRepository
                ?: return AdminResult.Failure(AdminError.Validation("Portal config repository not configured."))
        val tenant =
            tenantRepository.findBySlug(slug)
                ?: return AdminResult.Failure(AdminError.NotFound("Workspace '$slug' not found."))

        val saved = repo.upsert(tenant.id, PortalConfig(layout = layout))

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_TENANT_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("slug" to slug, "action" to "portal_layout_updated", "layout" to layout.name),
            ),
        )

        return AdminResult.Success(saved)
    }

    private fun validateWorkspaceSettings(
        update: WorkspaceSettingsUpdate,
        tenant: Tenant,
    ): AdminError.Validation? {
        if (update.displayName.isBlank()) {
            return AdminError.Validation("Display name is required.")
        }
        if (update.tokenExpirySeconds < 60) {
            return AdminError.Validation("Token expiry must be at least 60 seconds.")
        }
        if (update.refreshTokenExpirySeconds < update.tokenExpirySeconds) {
            return AdminError.Validation("Refresh token expiry must be ≥ access token expiry.")
        }
        if (update.passwordPolicyMinLength < 4 || update.passwordPolicyMinLength > 128) {
            return AdminError.Validation("Password minimum length must be between 4 and 128.")
        }
        if (update.mfaPolicy !in listOf("optional", "required", "required_admins")) {
            return AdminError.Validation("MFA policy must be 'optional', 'required', or 'required_admins'.")
        }
        if (tenant.isMaster && !update.passwordLoginEnabled) {
            return AdminError.Validation("Password sign-in cannot be disabled on the master workspace.")
        }
        if (!update.passwordLoginEnabled && !update.magicLinkEnabled) {
            return AdminError.Validation(
                "At least one email-based sign-in method must remain enabled. " +
                    "Enable magic links before disabling password sign-in.",
            )
        }
        return null
    }

    private fun applyWorkspaceSettings(
        tenant: Tenant,
        update: WorkspaceSettingsUpdate,
    ): Tenant =
        tenant.copy(
            displayName = update.displayName.trim(),
            issuerUrl = update.issuerUrl?.trim()?.takeIf { it.isNotBlank() },
            tokenExpirySeconds = update.tokenExpirySeconds,
            refreshTokenExpirySeconds = update.refreshTokenExpirySeconds,
            registrationEnabled = update.registrationEnabled,
            emailVerificationRequired = update.emailVerificationRequired,
            securityConfig =
                tenant.securityConfig.copy(
                    passwordMinLength = update.passwordPolicyMinLength,
                    passwordRequireSpecial = update.passwordPolicyRequireSpecial,
                    passwordRequireUppercase = update.passwordPolicyRequireUppercase,
                    passwordRequireNumber = update.passwordPolicyRequireNumber,
                    passwordHistoryCount = update.passwordPolicyHistoryCount.coerceIn(0, 24),
                    passwordMaxAgeDays = update.passwordPolicyMaxAgeDays.coerceIn(0, 365),
                    passwordBlacklistEnabled = update.passwordPolicyBlacklistEnabled,
                    mfaPolicy = update.mfaPolicy,
                    lockoutMaxAttempts = update.lockoutMaxAttempts.coerceAtLeast(0),
                    lockoutDurationMinutes = update.lockoutDurationMinutes.coerceAtLeast(1),
                    corsAllowCredentials = update.corsAllowCredentials,
                    hibpCheckEnabled = update.hibpCheckEnabled,
                    magicLinkEnabled = update.magicLinkEnabled,
                    magicLinkTokenTtlMinutes = update.magicLinkTokenTtlMinutes.coerceIn(1, 1440),
                    passwordLoginEnabled = update.passwordLoginEnabled,
                    emailOtpSignupEnabled = update.emailOtpSignupEnabled,
                    emailOtpLockoutThreshold = update.emailOtpLockoutThreshold.coerceIn(0, 50),
                    emailOtpLoginEnabled = update.emailOtpLoginEnabled,
                    loginIdentifierMode = update.loginIdentifierMode,
                ),
            passkeysEnabled = update.passkeysEnabled,
        )

    private fun recordWorkspaceSettingsAudit(
        before: Tenant,
        update: WorkspaceSettingsUpdate,
    ) {
        auditLog.record(
            AuditEvent(
                tenantId = before.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_TENANT_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("slug" to before.slug, "displayName" to update.displayName),
            ),
        )

        if (before.securityConfig.passwordLoginEnabled != update.passwordLoginEnabled) {
            auditLog.record(
                AuditEvent(
                    tenantId = before.id,
                    userId = null,
                    clientId = null,
                    eventType = AuditEventType.ADMIN_SECURITY_CONFIG_UPDATED,
                    ipAddress = null,
                    userAgent = null,
                    details =
                        mapOf(
                            "field" to "passwordLoginEnabled",
                            "value" to update.passwordLoginEnabled.toString(),
                        ),
                ),
            )
        }
    }

    private fun sanitizeTheme(theme: TenantTheme): TenantTheme =
        TenantTheme(
            accentColor = theme.accentColor.trim().ifBlank { TenantTheme.DEFAULT.accentColor },
            accentHoverColor = theme.accentHoverColor.trim().ifBlank { TenantTheme.DEFAULT.accentHoverColor },
            accentForeground = theme.accentForeground.trim().ifBlank { TenantTheme.DEFAULT.accentForeground },
            bgDeep = theme.bgDeep.trim().ifBlank { TenantTheme.DEFAULT.bgDeep },
            surface = theme.surface.trim().ifBlank { TenantTheme.DEFAULT.surface },
            fontFamily = theme.fontFamily.trim().ifBlank { TenantTheme.DEFAULT.fontFamily },
            bgInput = theme.bgInput.trim().ifBlank { TenantTheme.DEFAULT.bgInput },
            borderColor = theme.borderColor.trim().ifBlank { TenantTheme.DEFAULT.borderColor },
            borderRadius = theme.borderRadius.trim().ifBlank { TenantTheme.DEFAULT.borderRadius },
            textPrimary = theme.textPrimary.trim().ifBlank { TenantTheme.DEFAULT.textPrimary },
            textMuted = theme.textMuted.trim().ifBlank { TenantTheme.DEFAULT.textMuted },
            logoUrl = theme.logoUrl?.trim()?.takeIf { it.isNotBlank() },
            faviconUrl = theme.faviconUrl?.trim()?.takeIf { it.isNotBlank() },
            defaultLocale =
                theme.defaultLocale
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() },
            loginLayout = theme.loginLayout,
            loginBackgroundUrl = theme.loginBackgroundUrl?.trim()?.takeIf { it.isNotBlank() },
            loginTagline = theme.loginTagline?.trim()?.takeIf { it.isNotBlank() },
        )
}
