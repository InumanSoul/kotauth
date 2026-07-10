package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.MethodKey
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WorkspaceSettingsUpdate
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.TranslationPort
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.AdminError
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.WorkspaceSettingsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

fun Route.adminSettingsRoutes(
    accountService: AdminAccountService,
    workspaceSettingsService: WorkspaceSettingsService,
    adminUserService: com.kauth.domain.service.AdminUserService,
    userRepository: UserRepository,
    identityProviderRepository: IdentityProviderRepository?,
    mfaRepository: MfaRepository?,
    translationPort: TranslationPort,
    webAuthnCredentialRepository: com.kauth.domain.port.WebAuthnCredentialRepository? = null,
    securityMethodsService: com.kauth.domain.service.SecurityMethodsService? = null,
) {
    // -------------------------------------------------------------------
    // General workspace settings
    // -------------------------------------------------------------------

    get("/settings") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val saved = call.request.queryParameters["saved"] == "true"
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.workspaceSettingsPage(workspace, wsPairs, session.username, saved = saved),
        )
    }

    post("/settings") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val params = call.receiveParameters()
        val update =
            WorkspaceSettingsUpdate.from(workspace).copy(
                displayName = params["displayName"]?.trim() ?: "",
                issuerUrl = params["issuerUrl"]?.trim()?.takeIf { it.isNotBlank() },
                tokenExpirySeconds = params["tokenExpirySeconds"]?.toLongOrNull() ?: 3600L,
                refreshTokenExpirySeconds = params["refreshTokenExpirySeconds"]?.toLongOrNull() ?: 86400L,
                registrationEnabled = params["registrationEnabled"] == "true",
                emailVerificationRequired = params["emailVerificationRequired"] == "true",
            )
        when (val result = workspaceSettingsService.updateWorkspaceSettings(slug, update)) {
            is AdminResult.Success -> {
                val portalLayout =
                    params["portalLayout"]?.let { runCatching { PortalLayout.valueOf(it) }.getOrNull() }
                if (portalLayout != null) {
                    workspaceSettingsService.updatePortalLayout(slug, portalLayout)
                }
                call.respondRedirect("/admin/workspaces/$slug/settings?saved=true")
            }
            is AdminResult.Failure -> {
                val wsPairs = call.attributes[WsPairsAttr]
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.workspaceSettingsPage(
                        workspace,
                        wsPairs,
                        session.username,
                        error = result.error.message,
                    ),
                )
            }
        }
    }

    // -------------------------------------------------------------------
    // SMTP settings
    // -------------------------------------------------------------------

    get("/settings/smtp") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val savedParam = call.request.queryParameters["saved"]
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.smtpSettingsPage(workspace, wsPairs, session.username, savedParam = savedParam),
        )
    }

    post("/settings/smtp") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val params = call.receiveParameters()
        when (
            val result =
                accountService.updateSmtpConfig(
                    slug = slug,
                    smtpHost = params["smtpHost"]?.trim()?.takeIf { it.isNotBlank() },
                    smtpPort = params["smtpPort"]?.toIntOrNull() ?: 587,
                    smtpUsername = params["smtpUsername"]?.trim()?.takeIf { it.isNotBlank() },
                    smtpPassword = params["smtpPassword"]?.takeIf { it.isNotBlank() },
                    smtpFromAddress = params["smtpFromAddress"]?.trim()?.takeIf { it.isNotBlank() },
                    smtpFromName = params["smtpFromName"]?.trim()?.takeIf { it.isNotBlank() },
                    smtpTlsEnabled = params["smtpTlsEnabled"] == "true",
                    smtpEnabled = params["smtpEnabled"] == "true",
                )
        ) {
            is AdminResult.Success ->
                call.respondRedirect("/admin/workspaces/$slug/settings/smtp?saved=true")
            is AdminResult.Failure -> {
                val wsPairs = call.attributes[WsPairsAttr]
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.smtpSettingsPage(
                        workspace,
                        wsPairs,
                        session.username,
                        error = result.error.message,
                    ),
                )
            }
        }
    }

    post("/settings/smtp/test") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val adminUser =
            (
                adminUserService.getUser(
                    UserId(session.userId),
                    TenantId(session.tenantId),
                ) as? AdminResult.Success
            )?.value
        val recipientEmail = adminUser?.email ?: "${session.username}@localhost"
        when (val result = adminUserService.sendTestEmail(workspace.id, recipientEmail)) {
            is AdminResult.Success ->
                call.respondRedirect("/admin/workspaces/$slug/settings/smtp?saved=test_sent")
            is AdminResult.Failure -> {
                val wsPairs = call.attributes[WsPairsAttr]
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.smtpSettingsPage(
                        workspace,
                        wsPairs,
                        session.username,
                        error = result.error.message,
                    ),
                )
            }
        }
    }

    // -------------------------------------------------------------------
    // Identity Providers
    // -------------------------------------------------------------------

    get("/settings/identity-providers") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val providers = identityProviderRepository?.findAllByTenant(workspace.id) ?: emptyList()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.identityProvidersPage(
                workspace = workspace,
                providers = providers,
                allWorkspaces = wsPairs,
                loggedInAs = session.username,
            ),
        )
    }

    post("/settings/identity-providers/{provider}") {
        val session = call.sessions.get<AdminSession>()!!
        val provName = call.parameters["provider"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val provider =
            SocialProvider.fromValueOrNull(provName)
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Unsupported provider: $provName")

        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val params = call.receiveParameters()

        val newClientId = params["clientId"]?.trim() ?: ""
        val newSecret = params["clientSecret"]?.takeIf { it.isNotBlank() }
        val enabled = params["enabled"] == "true"

        if (newClientId.isBlank()) {
            val providers = identityProviderRepository?.findAllByTenant(workspace.id) ?: emptyList()
            return@post call.respondHtml(
                HttpStatusCode.UnprocessableEntity,
                AdminView.identityProvidersPage(
                    workspace = workspace,
                    providers = providers,
                    allWorkspaces = wsPairs,
                    loggedInAs = session.username,
                    error = "Client ID is required.",
                ),
            )
        }

        val idpRepo =
            identityProviderRepository ?: return@post call.respond(
                HttpStatusCode.NotImplemented,
                "Identity provider repository not configured",
            )

        val existing = idpRepo.findByTenantAndProvider(workspace.id, provider)
        if (existing == null) {
            if (newSecret.isNullOrBlank()) {
                val providers = idpRepo.findAllByTenant(workspace.id)
                return@post call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.identityProvidersPage(
                        workspace = workspace,
                        providers = providers,
                        allWorkspaces = wsPairs,
                        loggedInAs = session.username,
                        error = "Client Secret is required when adding a new provider.",
                    ),
                )
            }
            idpRepo.save(
                IdentityProvider(
                    tenantId = workspace.id,
                    provider = provider,
                    clientId = newClientId,
                    clientSecret = newSecret,
                    enabled = enabled,
                ),
            )
        } else {
            val secretToUse = newSecret ?: existing.clientSecret
            idpRepo.update(
                existing.copy(
                    clientId = newClientId,
                    clientSecret = secretToUse,
                    enabled = enabled,
                ),
            )
        }

        val slug = workspace.slug
        call.respondRedirect("/admin/workspaces/$slug/settings/identity-providers?saved=true")
    }

    post("/settings/identity-providers/{provider}/delete") {
        val provName = call.parameters["provider"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val provider =
            SocialProvider.fromValueOrNull(provName)
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        identityProviderRepository?.delete(workspace.id, provider)
        call.respondRedirect("/admin/workspaces/$slug/settings/identity-providers")
    }

    // -------------------------------------------------------------------
    // Security policy
    // -------------------------------------------------------------------

    get("/settings/security") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val savedParam = call.request.queryParameters["saved"]
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.securityPolicyPage(
                workspace,
                wsPairs,
                session.username,
                savedParam = savedParam,
            ),
        )
    }

    post("/settings/security") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val wsPairs = call.attributes[WsPairsAttr]
        val params = call.receiveParameters()
        val s = workspace.securityConfig

        val update =
            WorkspaceSettingsUpdate.from(workspace).copy(
                passwordPolicyMinLength = params["passwordPolicyMinLength"]?.toIntOrNull() ?: 8,
                passwordPolicyRequireSpecial = params["passwordPolicyRequireSpecial"] == "true",
                passwordPolicyRequireUppercase = params["passwordPolicyRequireUppercase"] == "true",
                passwordPolicyRequireNumber = params["passwordPolicyRequireNumber"] == "true",
                passwordPolicyHistoryCount = params["passwordPolicyHistoryCount"]?.toIntOrNull() ?: 0,
                passwordPolicyMaxAgeDays = params["passwordPolicyMaxAgeDays"]?.toIntOrNull() ?: 0,
                passwordPolicyBlacklistEnabled = params["passwordPolicyBlacklistEnabled"] == "true",
                mfaPolicy = params["mfaPolicy"]?.trim() ?: "optional",
                lockoutMaxAttempts = params["lockoutMaxAttempts"]?.toIntOrNull() ?: s.lockoutMaxAttempts,
                lockoutDurationMinutes = params["lockoutDurationMinutes"]?.toIntOrNull() ?: s.lockoutDurationMinutes,
                corsAllowCredentials = params["corsAllowCredentials"] == "true",
                hibpCheckEnabled = params["hibpCheckEnabled"] == "true",
                magicLinkTokenTtlMinutes =
                    params["magicLinkTokenTtlMinutes"]?.toIntOrNull() ?: s.magicLinkTokenTtlMinutes,
            )

        when (val policyResult = workspaceSettingsService.updateWorkspaceSettings(slug, update)) {
            is AdminResult.Success ->
                call.respondRedirect("/admin/workspaces/$slug/settings/security?saved=true")
            is AdminResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.securityPolicyPage(
                        workspace,
                        wsPairs,
                        session.username,
                        error = policyResult.error.message,
                    ),
                )
        }
    }

    // -------------------------------------------------------------------
    // Sign-in Methods
    // -------------------------------------------------------------------

    get("/settings/sign-in-methods") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val savedParam = call.request.queryParameters["saved"]
        val rows = securityMethodsService?.list(workspace) ?: emptyList()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.signInMethodsPage(
                workspace,
                wsPairs,
                session.username,
                rows = rows,
                toastMessage = if (savedParam == "methods") EnglishStrings.TOAST_SIGN_IN_METHODS_SAVED else null,
            ),
        )
    }

    post("/settings/sign-in-methods") {
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val wsPairs = call.attributes[WsPairsAttr]
        val params = call.receiveParameters()
        val session = call.sessions.get<AdminSession>()!!

        val svc = securityMethodsService
        if (svc == null) {
            call.respondRedirect("/admin/workspaces/$slug/settings/sign-in-methods?saved=methods")
            return@post
        }

        // Only include keys the current row set allows toggling; drop any that the
        // browser shouldn't have submitted (e.g. unconfigured social providers).
        val toggleableKeys =
            svc
                .list(workspace)
                .filter { it.toggleable }
                .map { it.key }
                .toSet()
        val requested =
            MethodKey.entries
                .filter { it in toggleableKeys }
                .associateWith { key -> params["enabled_${key.name.lowercase()}"] == "on" }

        when (val methodResult = svc.updateSecurityMethods(workspace.id, requested)) {
            is AdminResult.Success ->
                call.respondRedirect("/admin/workspaces/$slug/settings/sign-in-methods?saved=methods")
            is AdminResult.Failure -> {
                val (status, errorCode) =
                    when (methodResult.error) {
                        AdminError.NoMethodsEnabled -> HttpStatusCode.BadRequest to "NoMethodsEnabled"
                        AdminError.SmtpRequired -> HttpStatusCode.BadRequest to "SmtpRequired"
                        is AdminError.NotFound -> HttpStatusCode.NotFound to "NotFound"
                        else -> HttpStatusCode.BadRequest to "UnknownError"
                    }
                call.respond(status, mapOf("error" to errorCode))
            }
        }
    }

    // -------------------------------------------------------------------
    // Branding
    // -------------------------------------------------------------------

    get("/settings/branding") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val saved = call.request.queryParameters["saved"] == "true"
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.brandingPage(
                workspace,
                wsPairs,
                session.username,
                availableLocales = translationPort.availableLocales,
                saved = saved,
            ),
        )
    }

    post("/settings/branding") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val params = call.receiveParameters()
        val rawLocale = params["themeDefaultLocale"]?.trim()?.lowercase()
        val resolvedLocale =
            rawLocale
                ?.takeIf { it.isNotBlank() && translationPort.availableLocales.contains(it) }
        val theme =
            TenantTheme(
                accentColor = params["themeAccentColor"]?.trim() ?: workspace.theme.accentColor,
                accentHoverColor = params["themeAccentHover"]?.trim() ?: workspace.theme.accentHoverColor,
                accentForeground = params["themeAccentForeground"]?.trim() ?: workspace.theme.accentForeground,
                bgDeep = params["themeBgDeep"]?.trim() ?: workspace.theme.bgDeep,
                surface = params["themeSurface"]?.trim() ?: workspace.theme.surface,
                fontFamily = params["themeFontFamily"]?.trim() ?: workspace.theme.fontFamily,
                bgInput = params["themeBgInput"]?.trim() ?: workspace.theme.bgInput,
                borderColor = params["themeBorderColor"]?.trim() ?: workspace.theme.borderColor,
                borderRadius = params["themeBorderRadius"]?.trim() ?: workspace.theme.borderRadius,
                textPrimary = params["themeTextPrimary"]?.trim() ?: workspace.theme.textPrimary,
                textMuted = params["themeTextMuted"]?.trim() ?: workspace.theme.textMuted,
                logoUrl = params["themeLogoUrl"]?.trim()?.takeIf { it.isNotBlank() },
                faviconUrl = params["themeFaviconUrl"]?.trim()?.takeIf { it.isNotBlank() },
                defaultLocale = resolvedLocale,
            )
        when (val result = workspaceSettingsService.updateTheme(slug, theme)) {
            is AdminResult.Success -> {
                val existingBranding = workspace.emailBranding
                val emailBrandingResult =
                    workspaceSettingsService.updateEmailBranding(
                        slug,
                        existingBranding?.copy(
                            supportEmail = params["emailSupportEmail"]?.trim()?.takeIf { it.isNotBlank() },
                        ) ?: com.kauth.domain.model.TenantEmailBranding(
                            tenantId = workspace.id,
                            supportEmail = params["emailSupportEmail"]?.trim()?.takeIf { it.isNotBlank() },
                        ),
                    )
                if (emailBrandingResult is AdminResult.Failure) {
                    val wsPairs = call.attributes[WsPairsAttr]
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.brandingPage(
                            workspace,
                            wsPairs,
                            session.username,
                            availableLocales = translationPort.availableLocales,
                            error = emailBrandingResult.error.message,
                        ),
                    )
                } else {
                    call.respondRedirect("/admin/workspaces/$slug/settings/branding?saved=true")
                }
            }
            is AdminResult.Failure -> {
                val wsPairs = call.attributes[WsPairsAttr]
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.brandingPage(
                        workspace,
                        wsPairs,
                        session.username,
                        availableLocales = translationPort.availableLocales,
                        error = result.error.message,
                    ),
                )
            }
        }
    }

    // -------------------------------------------------------------------
    // Passkeys workspace page
    // -------------------------------------------------------------------

    get("/settings/passkeys") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val users = userRepository.findByTenantId(workspace.id, null)
        val enrolledUserIds = webAuthnCredentialRepository?.findUserIdsWithCredential(workspace.id) ?: emptySet()
        val userWithPasskey = users.map { u -> u to (u.id in enrolledUserIds) }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.passkeysPage(workspace, wsPairs, session.username, userWithPasskey),
        )
    }

    // -------------------------------------------------------------------
    // MFA overview
    // -------------------------------------------------------------------

    get("/mfa") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val users = userRepository.findByTenantId(workspace.id, null)
        val (enrolled, notEnrolled) =
            if (mfaRepository != null) {
                users.partition { u -> mfaRepository.findEnrollmentByUserId(u.id!!)?.verified == true }
            } else {
                emptyList<com.kauth.domain.model.User>() to users
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.mfaSettingsPage(
                workspace,
                wsPairs,
                session.username,
                totalUsers = users.size,
                enrolledUsers = enrolled.size,
                enrolledUserList = enrolled,
                notEnrolledUserList = notEnrolled,
            ),
        )
    }
}
