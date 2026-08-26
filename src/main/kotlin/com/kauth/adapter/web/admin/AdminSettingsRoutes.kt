package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.DEFAULT_OIDC_SCOPES
import com.kauth.domain.model.LoginLayout
import com.kauth.domain.model.MethodKey
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WorkspaceSettingsUpdate
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.TranslationPort
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.AdminError
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.IdentityProviderService
import com.kauth.domain.service.WorkspaceSettingsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
    identityProviderService: IdentityProviderService?,
    mfaRepository: MfaRepository?,
    translationPort: TranslationPort,
    webAuthnCredentialRepository: com.kauth.domain.port.WebAuthnCredentialRepository? = null,
    securityMethodsService: com.kauth.domain.service.SecurityMethodsService? = null,
    auditLogRepository: AuditLogRepository? = null,
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

    // One validation point for both surfaces. The form used to write the repository directly,
    // so the REST API's rules (issuer required for OIDC, https-only URLs, an immutable key)
    // did not apply here; every write below now goes through the same service.
    val idpService = identityProviderService ?: identityProviderRepository?.let { IdentityProviderService(it) }

    get("/settings/identity-providers") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val providers = idpService?.list(workspace.id) ?: emptyList()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.identityProvidersPage(
                workspace = workspace,
                providers = providers,
                allWorkspaces = wsPairs,
                loggedInAs = session.username,
                saved = call.request.queryParameters["saved"] == "true",
                failures = auditLogRepository.recentSignInFailures(workspace.id),
            ),
        )
    }

    // Two entry points, one handler: the add form has no key in its URL yet, so it posts the
    // collection and names the key in the body.
    post("/settings/identity-providers") {
        val service =
            idpService ?: return@post call.respond(
                HttpStatusCode.NotImplemented,
                "Identity provider repository not configured",
            )
        val params = call.receiveParameters()
        val provName = params["providerKey"]?.trim() ?: ""
        val provider =
            ProviderKey.of(provName)
                ?: return@post call.respondIdentityProviderError(
                    service,
                    EnglishStrings.IDP_KEY_INVALID,
                    auditLogRepository,
                )
        call.saveIdentityProvider(service, provider, params, auditLogRepository)
    }

    post("/settings/identity-providers/{provider}") {
        val provName = call.parameters["provider"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        // Any key the pattern accepts is configurable: the reserved two reach a compiled-in
        // adapter, everything else is brokered over OIDC.
        val provider =
            ProviderKey.of(provName)
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Unsupported provider: $provName")

        val service =
            idpService ?: return@post call.respond(
                HttpStatusCode.NotImplemented,
                "Identity provider repository not configured",
            )
        call.saveIdentityProvider(service, provider, call.receiveParameters(), auditLogRepository)
    }

    post("/settings/identity-providers/{provider}/delete") {
        val provName = call.parameters["provider"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val provider =
            ProviderKey.of(provName)
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        val workspace = call.attributes[WorkspaceAttr]
        // A delete of a row that is already gone is what the operator asked for, so the
        // service's NotFound is not worth a page of its own.
        idpService?.delete(workspace.id, provider)
        call.respondRedirect("/admin/workspaces/${workspace.slug}/settings/identity-providers")
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
                loginLayout =
                    params["themeLoginLayout"]?.let {
                        runCatching { LoginLayout.valueOf(it.uppercase()) }.getOrNull()
                    } ?: workspace.theme.loginLayout,
                loginBackgroundUrl = params["themeLoginBackgroundUrl"]?.trim()?.takeIf { it.isNotBlank() },
                loginTagline = params["themeLoginTagline"]?.trim()?.takeIf { it.isNotBlank() },
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

/**
 * The one identity-provider write the admin UI performs. Both the add form and a provider's
 * own edit form land here, so the service's rules reach every write from this surface.
 */
private suspend fun ApplicationCall.saveIdentityProvider(
    service: IdentityProviderService,
    provider: ProviderKey,
    params: io.ktor.http.Parameters,
    auditLogRepository: AuditLogRepository?,
) {
    val workspace = attributes[WorkspaceAttr]
    val existing = service.get(workspace.id, provider)
    val kind =
        params["kind"]?.trim()?.lowercase()?.let { ProviderKind.of(it) }
            ?: existing?.kind
            ?: if (provider in ProviderKey.RESERVED) ProviderKind.OAUTH2 else ProviderKind.OIDC

    val result =
        service.save(
            tenantId = workspace.id,
            key = provider,
            clientId = params["clientId"] ?: "",
            clientSecret = params["clientSecret"],
            kind = kind,
            enabled = params["enabled"] == "true",
            displayName = params["displayName"],
            issuer = params["issuer"],
            authorizationEndpoint = params["authorizationEndpoint"],
            tokenEndpoint = params["tokenEndpoint"],
            jwksUri = params["jwksUri"],
            scopes = params["scopes"] ?: existing?.scopes ?: DEFAULT_OIDC_SCOPES,
            // Phase 3 owns the JIT fields; this form does not show them, so it must not clear
            // what a row already carries.
            jitEnabled = existing?.jitEnabled ?: false,
            jitAllowedDomains = existing?.jitAllowedDomains ?: emptyList(),
        )

    when (result) {
        is AdminResult.Success ->
            respondRedirect("/admin/workspaces/${workspace.slug}/settings/identity-providers?saved=true")
        is AdminResult.Failure -> respondIdentityProviderError(service, result.error.message, auditLogRepository)
    }
}

/** Re-renders the identity provider page with [error] at 422. */
private suspend fun ApplicationCall.respondIdentityProviderError(
    service: IdentityProviderService,
    error: String,
    auditLogRepository: AuditLogRepository?,
) {
    val workspace = attributes[WorkspaceAttr]
    respondHtml(
        HttpStatusCode.UnprocessableEntity,
        AdminView.identityProvidersPage(
            workspace = workspace,
            providers = service.list(workspace.id),
            allWorkspaces = attributes[WsPairsAttr],
            loggedInAs = sessions.get<AdminSession>()!!.username,
            error = error,
            failures = auditLogRepository.recentSignInFailures(workspace.id),
        ),
    )
}

/**
 * The brokered sign-in failures this workspace has recorded lately, grouped by provider.
 *
 * One tenant-scoped read for the whole page: a per-card query would be one round trip per provider
 * for a panel that is usually empty.
 */
private fun AuditLogRepository?.recentSignInFailures(tenantId: TenantId) =
    this
        ?.findByTenant(
            tenantId = tenantId,
            eventType = AuditEventType.SOCIAL_LOGIN_FAILED,
            limit = RECENT_SIGN_IN_FAILURES,
        )?.groupSignInFailuresByProvider()
        .orEmpty()

/** Read across every provider, then trimmed per provider by the view. */
private const val RECENT_SIGN_IN_FAILURES = 200
