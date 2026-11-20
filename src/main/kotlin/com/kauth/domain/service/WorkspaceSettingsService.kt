package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.EMAIL_OTP_LOCKOUT_THRESHOLD
import com.kauth.domain.model.MAGIC_LINK_TTL_MINUTES
import com.kauth.domain.model.MAX_SESSIONS_PER_APP
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
        registrationEnabled: Boolean = true,
        emailVerificationRequired: Boolean = false,
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
            return AdminResult.Failure(AdminError.Conflict("A workspace with slug '$slug' already exists."))
        }
        val tenant =
            tenantRepository.create(slug, displayName, issuerUrl, registrationEnabled, emailVerificationRequired)
        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = null,
                eventType = AuditEventType.ADMIN_TENANT_CREATED,
                ipAddress = null,
                userAgent = null,
                details =
                    mapOf(
                        "slug" to slug,
                        "displayName" to displayName,
                        "registrationEnabled" to registrationEnabled.toString(),
                        "emailVerificationRequired" to emailVerificationRequired.toString(),
                    ),
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
    ): AdminError? {
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
        // Refused rather than coerced: a negative attempt count used to become 0, which turns lockout
        // off. Checked only on a change, so a value stored before these bounds still saves.
        val current = tenant.securityConfig
        if (update.lockoutMaxAttempts != current.lockoutMaxAttempts && update.lockoutMaxAttempts !in 0..100) {
            return AdminError.Validation("Max failed attempts must be between 0 (lockout off) and 100.")
        }
        if (update.lockoutDurationMinutes != current.lockoutDurationMinutes &&
            update.lockoutDurationMinutes !in 1..1440
        ) {
            return AdminError.Validation("Lockout duration must be between 1 and 1440 minutes.")
        }
        if (update.maxConcurrentSessions != tenant.maxConcurrentSessions &&
            update.maxConcurrentSessions != null &&
            update.maxConcurrentSessions !in MAX_SESSIONS_PER_APP
        ) {
            return AdminError.Validation(
                "Sessions per application must be between 1 and 100, or empty for no limit.",
            )
        }
        if (tenant.isMaster && !update.passwordLoginEnabled) {
            return AdminError.Validation("Password sign-in cannot be disabled on the master workspace.")
        }
        return validateSignInMethods(update, tenant)
    }

    // Same rules as the sign-in methods page (SecurityMethodsService), so a settings save can
    // never reach a configuration that page would refuse.
    private fun validateSignInMethods(
        update: WorkspaceSettingsUpdate,
        tenant: Tenant,
    ): AdminError? {
        val current = tenant.securityConfig
        if (!update.passwordLoginEnabled) {
            if (!update.magicLinkEnabled && !update.emailOtpLoginEnabled && !update.passkeysEnabled) {
                return AdminError.NoMethodsEnabled
            }
            if (current.passwordLoginEnabled && !tenant.isSmtpReady) return AdminError.SmtpRequired
        }
        val turnsOnEmailMethod =
            (update.magicLinkEnabled && !current.magicLinkEnabled) ||
                (update.emailOtpLoginEnabled && !current.emailOtpLoginEnabled)
        if (turnsOnEmailMethod && !tenant.isSmtpReady) {
            return AdminError.Validation(
                "SMTP must be configured and enabled before turning on magic links or email codes.",
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
                    lockoutMaxAttempts = update.lockoutMaxAttempts,
                    lockoutDurationMinutes = update.lockoutDurationMinutes,
                    corsAllowCredentials = update.corsAllowCredentials,
                    hibpCheckEnabled = update.hibpCheckEnabled,
                    magicLinkEnabled = update.magicLinkEnabled,
                    magicLinkTokenTtlMinutes = update.magicLinkTokenTtlMinutes.coerceIn(MAGIC_LINK_TTL_MINUTES),
                    passwordLoginEnabled = update.passwordLoginEnabled,
                    emailOtpSignupEnabled = update.emailOtpSignupEnabled,
                    emailOtpLockoutThreshold = update.emailOtpLockoutThreshold.coerceIn(EMAIL_OTP_LOCKOUT_THRESHOLD),
                    emailOtpLoginEnabled = update.emailOtpLoginEnabled,
                    loginIdentifierMode = update.loginIdentifierMode,
                ),
            passkeysEnabled = update.passkeysEnabled,
            maxConcurrentSessions = update.maxConcurrentSessions,
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
