package com.kauth.adapter.web.admin

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.RoleGroupService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.PortalClientProvisioning
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.time.Instant

/**
 * Admin console routes — Phase 3a (complete admin console).
 *
 * URL structure:
 *   /admin                                                   — workspace list (dashboard)
 *   /admin/workspaces/new                                    — create workspace
 *   /admin/workspaces/{slug}                                 — workspace detail
 *   /admin/workspaces/{slug}/settings                        — edit workspace settings
 *   /admin/workspaces/{slug}/applications/new                — create application
 *   /admin/workspaces/{slug}/applications/{clientId}         — application detail
 *   /admin/workspaces/{slug}/applications/{clientId}/edit    — edit application
 *   /admin/workspaces/{slug}/applications/{clientId}/toggle  — enable/disable
 *   /admin/workspaces/{slug}/applications/{clientId}/regenerate-secret
 *   /admin/workspaces/{slug}/users                           — user list
 *   /admin/workspaces/{slug}/users/new                       — create user
 *   /admin/workspaces/{slug}/users/{id}                      — user detail
 *   /admin/workspaces/{slug}/users/{id}/toggle               — enable/disable user
 *   /admin/workspaces/{slug}/users/{id}/edit                 — edit user profile
 *   /admin/workspaces/{slug}/users/{id}/revoke-sessions      — revoke all user sessions
 *   /admin/workspaces/{slug}/sessions                        — active sessions
 *   /admin/workspaces/{slug}/sessions/{id}/revoke            — revoke one session
 *   /admin/workspaces/{slug}/logs                            — audit log
 */
fun Route.adminRoutes(
    authService: AuthService,
    adminService: AdminService,
    roleGroupService: RoleGroupService,
    tenantRepository: TenantRepository,
    applicationRepository: ApplicationRepository,
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
    keyProvisioningService: KeyProvisioningService,
    mfaRepository: MfaRepository? = null,
    portalClientProvisioning: PortalClientProvisioning? = null,
    identityProviderRepository: IdentityProviderRepository? = null, // Phase 2 — Social Login
    apiKeyService: com.kauth.domain.service.ApiKeyService? = null, // Phase 3a — REST API keys
    webhookService: com.kauth.domain.service.WebhookService? = null, // Phase 4 — Webhooks
) {
    route("/admin") {
        // ---------------------------------------------------------------
        // Login / logout
        // ---------------------------------------------------------------

        get("/login") {
            if (call.sessions.get<AdminSession>() != null) {
                call.respondRedirect("/admin")
                return@get
            }
            call.respondHtml(HttpStatusCode.OK, AdminView.loginPage())
        }

        post("/login") {
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val password = params["password"] ?: ""
            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]
            // Use authenticate() so master-tenant login events appear in the audit log
            when (authService.authenticate(Tenant.MASTER_SLUG, username, password, ipAddress, userAgent)) {
                is AuthResult.Success -> {
                    call.sessions.set(AdminSession(username = username))
                    call.respondRedirect("/admin")
                }
                is AuthResult.Failure ->
                    call.respondHtml(
                        HttpStatusCode.Unauthorized,
                        AdminView.loginPage(error = "Invalid credentials."),
                    )
            }
        }

        post("/logout") {
            call.sessions.clear<AdminSession>()
            call.respondRedirect("/admin/login")
        }

        // ---------------------------------------------------------------
        // Session guard
        // ---------------------------------------------------------------

        intercept(ApplicationCallPipeline.Call) {
            if (!call.request.uri.startsWith("/admin/login") &&
                call.sessions.get<AdminSession>() == null
            ) {
                call.respondRedirect("/admin/login")
                finish()
            }
        }

        // ---------------------------------------------------------------
        // Dashboard
        // ---------------------------------------------------------------

        get {
            val session = call.sessions.get<AdminSession>()!!
            val workspaces = tenantRepository.findAll()
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.dashboardPage(workspaces, session.username),
            )
        }

        // ===============================================================
        // Workspaces
        // ===============================================================

        route("/workspaces") {
            get { call.respondRedirect("/admin") }

            get("/new") {
                val session = call.sessions.get<AdminSession>()!!
                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.createWorkspacePage(loggedInAs = session.username, allWorkspaces = wsPairs),
                )
            }

            post {
                val session = call.sessions.get<AdminSession>()!!
                val params = call.receiveParameters()
                val slug = params["slug"]?.trim()?.lowercase() ?: ""
                val displayName = params["displayName"]?.trim() ?: ""
                val issuerUrl = params["issuerUrl"]?.trim()?.takeIf { it.isNotBlank() }
                val prefill =
                    WorkspacePrefill(
                        slug = slug,
                        displayName = displayName,
                        issuerUrl = issuerUrl ?: "",
                        registrationEnabled = params["registrationEnabled"] == "true",
                        emailVerificationRequired = params["emailVerificationRequired"] == "true",
                        themeAccentColor = params["themeAccentColor"]?.trim() ?: "#1FBCFF",
                        themeLogoUrl = params["themeLogoUrl"]?.trim() ?: "",
                    )
                val error =
                    when {
                        slug.isBlank() -> "Slug is required."
                        !slug.matches(
                            Regex("[a-z0-9-]+"),
                        ) -> "Slug may only contain lowercase letters, numbers, and hyphens."
                        slug == Tenant.MASTER_SLUG -> "The slug 'master' is reserved."
                        displayName.isBlank() -> "Display name is required."
                        tenantRepository.existsBySlug(slug) -> "A workspace with slug '$slug' already exists."
                        else -> null
                    }
                if (error != null) {
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    return@post call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createWorkspacePage(
                            loggedInAs = session.username,
                            allWorkspaces = wsPairs,
                            error = error,
                            prefill = prefill,
                        ),
                    )
                }
                val newTenant = tenantRepository.create(slug, displayName, issuerUrl)
                keyProvisioningService.provisionForTenant(newTenant)
                // Provision portal client + redirect URI for the new tenant
                portalClientProvisioning?.provisionRedirectUris()
                // Seed the two default tenant-scoped roles every workspace needs.
                // 'admin' and 'user' are the minimal meaningful defaults — operators
                // can extend, rename, or delete them via the roles UI.
                roleGroupService.createRole(
                    newTenant.id,
                    "admin",
                    "Full administrative access within this workspace",
                    RoleScope.TENANT,
                    null,
                )
                roleGroupService.createRole(
                    newTenant.id,
                    "user",
                    "Standard authenticated user — default role for self-registrations",
                    RoleScope.TENANT,
                    null,
                )
                call.respondRedirect("/admin/workspaces/$slug")
            }

            // -----------------------------------------------------------
            // Per-workspace routes
            // -----------------------------------------------------------

            route("/{slug}") {
                // Workspace detail
                get {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val apps = applicationRepository.findByTenantId(workspace.id)
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.workspaceDetailPage(workspace, wsPairs, apps, session.username),
                    )
                }

                // -------------------------------------------------------
                // Workspace settings
                // -------------------------------------------------------

                get("/settings") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val saved = call.request.queryParameters["saved"] == "true"
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.workspaceSettingsPage(workspace, wsPairs, session.username, saved = saved),
                    )
                }

                // -------------------------------------------------------
                // SMTP settings (Phase 3b)
                // -------------------------------------------------------

                get("/settings/smtp") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val saved = call.request.queryParameters["saved"] == "true"
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.smtpSettingsPage(workspace, wsPairs, session.username, saved = saved),
                    )
                }

                post("/settings/smtp") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val params = call.receiveParameters()
                    when (
                        val result =
                            adminService.updateSmtpConfig(
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
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
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

                // ----------------------------------------------------------
                // Identity Providers — Phase 2 (Social Login)
                // ----------------------------------------------------------

                get("/settings/identity-providers") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val providers = identityProviderRepository?.findAllByTenant(workspace.id) ?: emptyList()
                    val editParam = call.request.queryParameters["edit"]
                    val editProvider = editParam?.let { SocialProvider.fromValueOrNull(it) }
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.identityProvidersPage(
                            workspace = workspace,
                            providers = providers,
                            allWorkspaces = wsPairs,
                            loggedInAs = session.username,
                            editProvider = editProvider,
                        ),
                    )
                }

                post("/settings/identity-providers/{provider}") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val provName = call.parameters["provider"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val provider =
                        SocialProvider.fromValueOrNull(provName)
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Unsupported provider: $provName")

                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
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
                                editProvider = provider,
                                error = "Client ID is required.",
                            ),
                        )
                    }

                    val idpRepo =
                        identityProviderRepository ?: return@post call.respond(
                            HttpStatusCode.NotImplemented,
                            "Identity provider repository not configured",
                        )

                    if (!EncryptionService.isAvailable && newSecret != null) {
                        val providers = idpRepo.findAllByTenant(workspace.id)
                        return@post call.respondHtml(
                            HttpStatusCode.UnprocessableEntity,
                            AdminView.identityProvidersPage(
                                workspace = workspace,
                                providers = providers,
                                allWorkspaces = wsPairs,
                                loggedInAs = session.username,
                                editProvider = provider,
                                error = "KAUTH_SECRET_KEY must be set to store provider credentials securely.",
                            ),
                        )
                    }

                    val existing = idpRepo.findByTenantAndProvider(workspace.id, provider)
                    if (existing == null) {
                        // New provider
                        if (newSecret.isNullOrBlank()) {
                            val providers = idpRepo.findAllByTenant(workspace.id)
                            return@post call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.identityProvidersPage(
                                    workspace = workspace,
                                    providers = providers,
                                    allWorkspaces = wsPairs,
                                    loggedInAs = session.username,
                                    editProvider = provider,
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
                        // Update existing — preserve secret if not changed
                        val secretToUse = newSecret ?: existing.clientSecret
                        idpRepo.update(
                            existing.copy(
                                clientId = newClientId,
                                clientSecret = secretToUse,
                                enabled = enabled,
                            ),
                        )
                    }

                    call.respondRedirect("/admin/workspaces/$slug/settings/identity-providers?saved=true")
                }

                post("/settings/identity-providers/{provider}/delete") {
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val provName = call.parameters["provider"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val provider =
                        SocialProvider.fromValueOrNull(provName)
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    identityProviderRepository?.delete(workspace.id, provider)
                    call.respondRedirect("/admin/workspaces/$slug/settings/identity-providers")
                }

                post("/settings") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val params = call.receiveParameters()
                    when (
                        val result =
                            adminService.updateWorkspaceSettings(
                                slug = slug,
                                displayName = params["displayName"]?.trim() ?: "",
                                issuerUrl = params["issuerUrl"]?.trim()?.takeIf { it.isNotBlank() },
                                tokenExpirySeconds = params["tokenExpirySeconds"]?.toLongOrNull() ?: 3600L,
                                refreshTokenExpirySeconds =
                                    params["refreshTokenExpirySeconds"]?.toLongOrNull() ?: 86400L,
                                registrationEnabled = params["registrationEnabled"] == "true",
                                emailVerificationRequired = params["emailVerificationRequired"] == "true",
                                // Security fields not on this form — preserve existing values
                                passwordPolicyMinLength = workspace.passwordPolicyMinLength,
                                passwordPolicyRequireSpecial = workspace.passwordPolicyRequireSpecial,
                                passwordPolicyRequireUppercase = workspace.passwordPolicyRequireUppercase,
                                passwordPolicyRequireNumber = workspace.passwordPolicyRequireNumber,
                                passwordPolicyHistoryCount = workspace.passwordPolicyHistoryCount,
                                passwordPolicyMaxAgeDays = workspace.passwordPolicyMaxAgeDays,
                                passwordPolicyBlacklistEnabled = workspace.passwordPolicyBlacklistEnabled,
                                mfaPolicy = workspace.mfaPolicy,
                                themeAccentColor = params["themeAccentColor"]?.trim() ?: "#1FBCFF",
                                themeLogoUrl = params["themeLogoUrl"]?.trim()?.takeIf { it.isNotBlank() },
                                themeFaviconUrl = params["themeFaviconUrl"]?.trim()?.takeIf { it.isNotBlank() },
                            )
                    ) {
                        is AdminResult.Success ->
                            call.respondRedirect("/admin/workspaces/$slug/settings?saved=true")
                        is AdminResult.Failure -> {
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
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

                // -------------------------------------------------------
                // Security policy settings  (/settings/security)
                // -------------------------------------------------------

                get("/settings/security") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val saved = call.request.queryParameters["saved"] == "true"
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.securityPolicyPage(workspace, wsPairs, session.username, saved = saved),
                    )
                }

                post("/settings/security") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val params = call.receiveParameters()
                    when (
                        val result =
                            adminService.updateWorkspaceSettings(
                                slug = slug,
                                // General fields not on this form — preserve existing values
                                displayName = workspace.displayName,
                                issuerUrl = workspace.issuerUrl,
                                tokenExpirySeconds = workspace.tokenExpirySeconds,
                                refreshTokenExpirySeconds = workspace.refreshTokenExpirySeconds,
                                registrationEnabled = workspace.registrationEnabled,
                                emailVerificationRequired = workspace.emailVerificationRequired,
                                // Security fields from the form
                                passwordPolicyMinLength = params["passwordPolicyMinLength"]?.toIntOrNull() ?: 8,
                                passwordPolicyRequireSpecial = params["passwordPolicyRequireSpecial"] == "true",
                                passwordPolicyRequireUppercase = params["passwordPolicyRequireUppercase"] == "true",
                                passwordPolicyRequireNumber = params["passwordPolicyRequireNumber"] == "true",
                                passwordPolicyHistoryCount = params["passwordPolicyHistoryCount"]?.toIntOrNull() ?: 0,
                                passwordPolicyMaxAgeDays = params["passwordPolicyMaxAgeDays"]?.toIntOrNull() ?: 0,
                                passwordPolicyBlacklistEnabled = params["passwordPolicyBlacklistEnabled"] == "true",
                                mfaPolicy = params["mfaPolicy"]?.trim() ?: "optional",
                                // Theme fields not on this form — preserve existing values
                                themeAccentColor = workspace.theme.accentColor,
                                themeLogoUrl = workspace.theme.logoUrl,
                                themeFaviconUrl = workspace.theme.faviconUrl,
                            )
                    ) {
                        is AdminResult.Success ->
                            call.respondRedirect("/admin/workspaces/$slug/settings/security?saved=true")
                        is AdminResult.Failure -> {
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.securityPolicyPage(
                                    workspace,
                                    wsPairs,
                                    session.username,
                                    error = result.error.message,
                                ),
                            )
                        }
                    }
                }

                // -------------------------------------------------------
                // Applications
                // -------------------------------------------------------

                route("/applications") {
                    get("/new") {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                        call.respondHtml(
                            HttpStatusCode.OK,
                            AdminView.createApplicationPage(workspace, wsPairs, session.username),
                        )
                    }

                    post {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                        val params = call.receiveParameters()
                        val clientId = params["clientId"]?.trim()?.lowercase() ?: ""
                        val name = params["name"]?.trim() ?: ""
                        val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
                        val accessType = params["accessType"]?.trim() ?: "public"
                        val redirectUris =
                            params["redirectUris"]
                                ?.lines()
                                ?.map { it.trim() }
                                ?.filter { it.isNotBlank() } ?: emptyList()
                        val prefill =
                            ApplicationPrefill(
                                clientId = clientId,
                                name = name,
                                description = desc ?: "",
                                accessType = accessType,
                                redirectUris = params["redirectUris"] ?: "",
                            )
                        val error =
                            when {
                                clientId.isBlank() -> "Client ID is required."
                                !clientId.matches(
                                    Regex("[a-z0-9-]+"),
                                ) -> "Client ID may only contain lowercase letters, numbers, and hyphens."
                                applicationRepository.existsByClientId(
                                    workspace.id,
                                    clientId,
                                ) -> "Client ID '$clientId' already exists."
                                name.isBlank() -> "Name is required."
                                else -> null
                            }
                        if (error != null) {
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            return@post call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.createApplicationPage(
                                    workspace,
                                    wsPairs,
                                    session.username,
                                    error = error,
                                    prefill = prefill,
                                ),
                            )
                        }
                        applicationRepository.create(workspace.id, clientId, name, desc, accessType, redirectUris)
                        call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                    }

                    route("/{clientId}") {
                        get {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug =
                                call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val clientId =
                                call.parameters["clientId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val app =
                                applicationRepository.findByClientId(workspace.id, clientId)
                                    ?: return@get call.respond(HttpStatusCode.NotFound)
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            val allApps = applicationRepository.findByTenantId(workspace.id)
                            val newSecret = call.request.queryParameters["newSecret"]
                            call.respondHtml(
                                HttpStatusCode.OK,
                                AdminView.applicationDetailPage(
                                    workspace,
                                    app,
                                    wsPairs,
                                    allApps,
                                    session.username,
                                    newSecret,
                                ),
                            )
                        }

                        get("/edit") {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug =
                                call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val clientId =
                                call.parameters["clientId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val app =
                                applicationRepository.findByClientId(workspace.id, clientId)
                                    ?: return@get call.respond(HttpStatusCode.NotFound)
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            val allApps = applicationRepository.findByTenantId(workspace.id)
                            call.respondHtml(
                                HttpStatusCode.OK,
                                AdminView.editApplicationPage(workspace, app, wsPairs, allApps, session.username),
                            )
                        }

                        post("/edit") {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val clientId =
                                call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val app =
                                applicationRepository.findByClientId(workspace.id, clientId)
                                    ?: return@post call.respond(HttpStatusCode.NotFound)
                            val params = call.receiveParameters()
                            val name = params["name"]?.trim() ?: ""
                            val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
                            val accessType = params["accessType"]?.trim() ?: app.accessType.value
                            val redirectUris =
                                params["redirectUris"]
                                    ?.lines()
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotBlank() } ?: emptyList()
                            when (
                                val result =
                                    adminService.updateApplication(
                                        app.id,
                                        workspace.id,
                                        name,
                                        desc,
                                        accessType,
                                        redirectUris,
                                    )
                            ) {
                                is AdminResult.Success ->
                                    call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                                is AdminResult.Failure -> {
                                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                                    val allApps = applicationRepository.findByTenantId(workspace.id)
                                    call.respondHtml(
                                        HttpStatusCode.UnprocessableEntity,
                                        AdminView.editApplicationPage(
                                            workspace,
                                            app,
                                            wsPairs,
                                            allApps,
                                            session.username,
                                            error = result.error.message,
                                        ),
                                    )
                                }
                            }
                        }

                        post("/toggle") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val clientId =
                                call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val app =
                                applicationRepository.findByClientId(workspace.id, clientId)
                                    ?: return@post call.respond(HttpStatusCode.NotFound)
                            adminService.setApplicationEnabled(app.id, workspace.id, !app.enabled)
                            call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                        }

                        post("/regenerate-secret") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val clientId =
                                call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val app =
                                applicationRepository.findByClientId(workspace.id, clientId)
                                    ?: return@post call.respond(HttpStatusCode.NotFound)
                            when (val result = adminService.regenerateClientSecret(app.id, workspace.id)) {
                                is AdminResult.Success -> {
                                    val encoded = java.net.URLEncoder.encode(result.value, "UTF-8")
                                    call.respondRedirect(
                                        "/admin/workspaces/$slug/applications/$clientId?newSecret=$encoded",
                                    )
                                }
                                is AdminResult.Failure ->
                                    call.respond(HttpStatusCode.BadRequest, result.error.message)
                            }
                        }
                    }
                }

                // -------------------------------------------------------
                // Users
                // -------------------------------------------------------

                route("/users") {
                    get {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val search =
                            call.request.queryParameters["q"]
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        val users = userRepository.findByTenantId(workspace.id, search)
                        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                        call.respondHtml(
                            HttpStatusCode.OK,
                            AdminView.userListPage(workspace, users, wsPairs, session.username, search),
                        )
                    }

                    get("/new") {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                        call.respondHtml(
                            HttpStatusCode.OK,
                            AdminView.createUserPage(workspace, wsPairs, session.username),
                        )
                    }

                    post {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                        val params = call.receiveParameters()
                        val username = params["username"]?.trim() ?: ""
                        val email = params["email"]?.trim() ?: ""
                        val fullName = params["fullName"]?.trim() ?: ""
                        val password = params["password"] ?: ""
                        when (val result = adminService.createUser(workspace.id, username, email, fullName, password)) {
                            is AdminResult.Success ->
                                call.respondRedirect("/admin/workspaces/$slug/users/${result.value.id}")
                            is AdminResult.Failure -> {
                                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                                val prefill = UserPrefill(username = username, email = email, fullName = fullName)
                                call.respondHtml(
                                    HttpStatusCode.UnprocessableEntity,
                                    AdminView.createUserPage(
                                        workspace,
                                        wsPairs,
                                        session.username,
                                        error = result.error.message,
                                        prefill = prefill,
                                    ),
                                )
                            }
                        }
                    }

                    route("/{userId}") {
                        get {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug =
                                call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val user =
                                userRepository.findById(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
                            if (user.tenantId != workspace.id) return@get call.respond(HttpStatusCode.NotFound)
                            val sessions = sessionRepository.findActiveByUser(workspace.id, userId)
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            val savedParam = call.request.queryParameters["saved"]
                            val errorParam = call.request.queryParameters["error"]
                            val successMsg =
                                when (savedParam) {
                                    "true" -> "Profile saved."
                                    "reset_email_sent" -> "Password reset email sent successfully."
                                    else -> null
                                }
                            call.respondHtml(
                                HttpStatusCode.OK,
                                AdminView.userDetailPage(
                                    workspace,
                                    user,
                                    sessions,
                                    wsPairs,
                                    session.username,
                                    successMessage = successMsg,
                                    editError = errorParam,
                                ),
                            )
                        }

                        post("/toggle") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val user =
                                userRepository.findById(userId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            if (user.tenantId != workspace.id) return@post call.respond(HttpStatusCode.NotFound)
                            adminService.setUserEnabled(userId, workspace.id, !user.enabled)
                            call.respondRedirect("/admin/workspaces/$slug/users/$userId")
                        }

                        post("/edit") {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val user =
                                userRepository.findById(userId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            if (user.tenantId != workspace.id) return@post call.respond(HttpStatusCode.NotFound)
                            val params = call.receiveParameters()
                            val email = params["email"]?.trim() ?: ""
                            val fullName = params["fullName"]?.trim() ?: ""
                            when (val result = adminService.updateUser(userId, workspace.id, email, fullName)) {
                                is AdminResult.Success ->
                                    call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=true")
                                is AdminResult.Failure -> {
                                    val sessions = sessionRepository.findActiveByUser(workspace.id, userId)
                                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                                    call.respondHtml(
                                        HttpStatusCode.UnprocessableEntity,
                                        AdminView.userDetailPage(
                                            workspace,
                                            user,
                                            sessions,
                                            wsPairs,
                                            session.username,
                                            editError = result.error.message,
                                        ),
                                    )
                                }
                            }
                        }

                        post("/revoke-sessions") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            sessionRepository.revokeAllForUser(workspace.id, userId)
                            call.respondRedirect("/admin/workspaces/$slug/users/$userId")
                        }

                        // Phase 3b: resend email verification
                        post("/send-verification") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                            adminService.resendVerificationEmail(userId, workspace.id, baseUrl)
                            call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=true")
                        }

                        // Phase 3c: send password-reset email instead of force-setting password
                        post("/send-reset-email") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                            when (val result = adminService.sendPasswordResetEmail(userId, workspace.id, baseUrl)) {
                                is AdminResult.Success ->
                                    call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=reset_email_sent")
                                is AdminResult.Failure ->
                                    call.respondRedirect(
                                        "/admin/workspaces/$slug/users/$userId?error=${java.net.URLEncoder.encode(
                                            result.error.message,
                                            "UTF-8",
                                        )}",
                                    )
                            }
                        }
                    }
                }

                // -------------------------------------------------------
                // Sessions
                // -------------------------------------------------------

                get("/sessions") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val sessions = sessionRepository.findActiveByTenant(workspace.id)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.activeSessionsPage(workspace, sessions, wsPairs, session.username),
                    )
                }

                post("/sessions/{sessionId}/revoke") {
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val sessionId =
                        call.parameters["sessionId"]?.toIntOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    sessionRepository.revoke(sessionId, Instant.now())
                    call.respondRedirect("/admin/workspaces/$slug/sessions")
                }

                // -------------------------------------------------------
                // Audit log
                // -------------------------------------------------------

                get("/logs") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val page =
                        call.request.queryParameters["page"]
                            ?.toIntOrNull()
                            ?.coerceAtLeast(1) ?: 1
                    val eventTypeStr = call.request.queryParameters["event"]
                    val eventType = eventTypeStr?.let { runCatching { AuditEventType.valueOf(it) }.getOrNull() }
                    val pageSize = 50
                    val offset = (page - 1) * pageSize
                    val events =
                        auditLogRepository.findByTenant(
                            workspace.id,
                            eventType,
                            limit = pageSize,
                            offset = offset,
                        )
                    val total = auditLogRepository.countByTenant(workspace.id, eventType)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.auditLogPage(
                            workspace,
                            events,
                            wsPairs,
                            session.username,
                            page = page,
                            totalPages = ((total + pageSize - 1) / pageSize).toInt(),
                            eventTypeFilter = eventTypeStr,
                        ),
                    )
                }

                // -------------------------------------------------------
                // Roles (Phase 3c) — HTML pages
                // -------------------------------------------------------

                route("/roles") {
                    get {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                        val roles = roleGroupService.listRoles(workspace.id)
                        call.respondHtml(
                            HttpStatusCode.OK,
                            AdminView.rolesListPage(workspace, roles, wsPairs, session.username),
                        )
                    }

                    // Separate create page — keeps the list clean
                    get("/create") {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                        val apps = applicationRepository.findByTenantId(workspace.id)
                        call.respondHtml(
                            HttpStatusCode.OK,
                            AdminView.createRolePage(workspace, apps, wsPairs, session.username),
                        )
                    }

                    post {
                        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                        val params = call.receiveParameters()
                        val name = params["name"]?.trim() ?: ""
                        val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
                        val scopeStr = params["scope"]?.trim() ?: "tenant"
                        val clientId = params["clientId"]?.toIntOrNull()

                        when (
                            val result =
                                roleGroupService.createRole(
                                    tenantId = workspace.id,
                                    name = name,
                                    description = desc,
                                    scope = RoleScope.fromValue(scopeStr),
                                    clientId = clientId,
                                )
                        ) {
                            is AdminResult.Success ->
                                call.respondRedirect("/admin/workspaces/$slug/roles")
                            is AdminResult.Failure -> {
                                val session = call.sessions.get<AdminSession>()!!
                                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                                val apps = applicationRepository.findByTenantId(workspace.id)
                                call.respondHtml(
                                    HttpStatusCode.UnprocessableEntity,
                                    AdminView.createRolePage(
                                        workspace,
                                        apps,
                                        wsPairs,
                                        session.username,
                                        error = result.error.message,
                                    ),
                                )
                            }
                        }
                    }

                    route("/{roleId}") {
                        get {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug =
                                call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            val roles = roleGroupService.listRoles(workspace.id)
                            val role =
                                roles.find { it.id == roleId } ?: return@get call.respond(HttpStatusCode.NotFound)
                            val users = userRepository.findByTenantId(workspace.id, null)
                            call.respondHtml(
                                HttpStatusCode.OK,
                                AdminView.roleDetailPage(workspace, role, roles, users, wsPairs, session.username),
                            )
                        }

                        post("/edit") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val params = call.receiveParameters()
                            roleGroupService.updateRole(
                                roleId,
                                workspace.id,
                                params["name"]?.trim() ?: "",
                                params["description"]?.trim()?.takeIf { it.isNotBlank() },
                            )
                            call.respondRedirect("/admin/workspaces/$slug/roles/$roleId")
                        }

                        post("/delete") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            roleGroupService.deleteRole(roleId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/roles")
                        }

                        post("/children") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val childId =
                                call.receiveParameters()["childRoleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.addChildRole(roleId, childId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/roles/$roleId")
                        }

                        post("/remove-child") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val childId =
                                call.receiveParameters()["childRoleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.removeChildRole(roleId, childId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/roles/$roleId")
                        }

                        post("/assign-user") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val userId =
                                call.receiveParameters()["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.assignRoleToUser(userId, roleId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/roles/$roleId")
                        }

                        post("/unassign-user") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val userId =
                                call.receiveParameters()["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.unassignRoleFromUser(userId, roleId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/roles/$roleId")
                        }
                    }
                }

                // -------------------------------------------------------
                // Groups (Phase 3c) — HTML pages
                // -------------------------------------------------------

                route("/groups") {
                    get {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                        val groups = roleGroupService.listGroups(workspace.id)
                        val roles = roleGroupService.listRoles(workspace.id)
                        call.respondHtml(
                            HttpStatusCode.OK,
                            AdminView.groupsListPage(workspace, groups, roles, wsPairs, session.username),
                        )
                    }

                    // Separate create page — keeps the list clean
                    get("/create") {
                        val session = call.sessions.get<AdminSession>()!!
                        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                        val groups = roleGroupService.listGroups(workspace.id)
                        call.respondHtml(
                            HttpStatusCode.OK,
                            AdminView.createGroupPage(workspace, groups, wsPairs, session.username),
                        )
                    }

                    post {
                        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val workspace =
                            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                        val params = call.receiveParameters()
                        val name = params["name"]?.trim() ?: ""
                        val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
                        val parentId = params["parentGroupId"]?.toIntOrNull()

                        when (val result = roleGroupService.createGroup(workspace.id, name, desc, parentId)) {
                            is AdminResult.Success ->
                                call.respondRedirect("/admin/workspaces/$slug/groups")
                            is AdminResult.Failure -> {
                                val session = call.sessions.get<AdminSession>()!!
                                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                                val groups = roleGroupService.listGroups(workspace.id)
                                call.respondHtml(
                                    HttpStatusCode.UnprocessableEntity,
                                    AdminView.createGroupPage(
                                        workspace,
                                        groups,
                                        wsPairs,
                                        session.username,
                                        error = result.error.message,
                                    ),
                                )
                            }
                        }
                    }

                    route("/{groupId}") {
                        get {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug =
                                call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            val groups = roleGroupService.listGroups(workspace.id)
                            val group =
                                groups.find { it.id == groupId } ?: return@get call.respond(HttpStatusCode.NotFound)
                            val roles = roleGroupService.listRoles(workspace.id)
                            val memberIds = roleGroupService.getUserIdsInGroup(groupId)
                            val members = memberIds.mapNotNull { userRepository.findById(it) }
                            val users = userRepository.findByTenantId(workspace.id, null)
                            call.respondHtml(
                                HttpStatusCode.OK,
                                AdminView.groupDetailPage(
                                    workspace,
                                    group,
                                    groups,
                                    roles,
                                    members,
                                    users,
                                    wsPairs,
                                    session.username,
                                ),
                            )
                        }

                        post("/edit") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val params = call.receiveParameters()
                            roleGroupService.updateGroup(
                                groupId,
                                workspace.id,
                                name = params["name"]?.trim() ?: "",
                                description = params["description"]?.trim()?.takeIf { it.isNotBlank() },
                            )
                            call.respondRedirect("/admin/workspaces/$slug/groups/$groupId")
                        }

                        post("/delete") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            roleGroupService.deleteGroup(groupId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/groups")
                        }

                        post("/assign-role") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val roleId =
                                call.receiveParameters()["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.assignRoleToGroup(groupId, roleId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/groups/$groupId")
                        }

                        post("/unassign-role") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val roleId =
                                call.receiveParameters()["roleId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.unassignRoleFromGroup(groupId, roleId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/groups/$groupId")
                        }

                        post("/add-member") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val userId =
                                call.receiveParameters()["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.addUserToGroup(userId, groupId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/groups/$groupId")
                        }

                        post("/remove-member") {
                            val slug =
                                call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace =
                                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val userId =
                                call.receiveParameters()["userId"]?.toIntOrNull()
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            roleGroupService.removeUserFromGroup(userId, groupId, workspace.id)
                            call.respondRedirect("/admin/workspaces/$slug/groups/$groupId")
                        }
                    }
                }

                // -------------------------------------------------------
                // MFA settings (Phase 3c) — HTML page
                // -------------------------------------------------------

                get("/mfa") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val users = userRepository.findByTenantId(workspace.id, null)
                    val mfaEnrolledCount =
                        if (mfaRepository != null) {
                            users.count { u -> mfaRepository.findEnrollmentByUserId(u.id!!)?.verified == true }
                        } else {
                            0
                        }
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.mfaSettingsPage(
                            workspace,
                            wsPairs,
                            session.username,
                            totalUsers = users.size,
                            enrolledUsers = mfaEnrolledCount,
                        ),
                    )
                }

                // -------------------------------------------------------
                // API Keys (Phase 3a) — machine-to-machine REST API auth
                // -------------------------------------------------------

                get("/settings/api-keys") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val keys = apiKeyService?.listForTenant(workspace.id) ?: emptyList()
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.apiKeysPage(workspace, keys, wsPairs, session.username),
                    )
                }

                post("/settings/api-keys") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val svc = apiKeyService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)

                    val params = call.receiveParameters()
                    val name = params["name"]?.trim() ?: ""
                    val scopes = params.getAll("scopes") ?: emptyList()
                    val expiresAt =
                        params["expiresAt"]?.takeIf { it.isNotBlank() }?.let {
                            runCatching {
                                java.time.LocalDate
                                    .parse(it)
                                    .atStartOfDay(java.time.ZoneId.of("UTC"))
                                    .toInstant()
                            }.getOrNull()
                        }

                    when (val result = svc.create(workspace.id, name, scopes, expiresAt)) {
                        is com.kauth.domain.service.ApiKeyResult.Success -> {
                            val keys = svc.listForTenant(workspace.id)
                            call.respondHtml(
                                HttpStatusCode.OK,
                                AdminView.apiKeysPage(
                                    workspace,
                                    keys,
                                    wsPairs,
                                    session.username,
                                    newKeyRaw = result.value.rawKey,
                                ),
                            )
                        }
                        is com.kauth.domain.service.ApiKeyResult.Failure -> {
                            val keys = svc.listForTenant(workspace.id)
                            call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.apiKeysPage(
                                    workspace,
                                    keys,
                                    wsPairs,
                                    session.username,
                                    error = result.error.message,
                                ),
                            )
                        }
                    }
                }

                post("/settings/api-keys/{keyId}/revoke") {
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val keyId =
                        call.parameters["keyId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    apiKeyService?.revoke(keyId, workspace.id)
                    call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
                }

                post("/settings/api-keys/{keyId}/delete") {
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val keyId =
                        call.parameters["keyId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    apiKeyService?.delete(keyId, workspace.id)
                    call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
                }

                // -------------------------------------------------------
                // Webhooks (Phase 4) — event-driven HTTP callbacks
                // -------------------------------------------------------

                get("/settings/webhooks") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val endpoints = webhookService?.listEndpoints(workspace.id) ?: emptyList()
                    val deliveries = webhookService?.recentDeliveries(workspace.id) ?: emptyList()
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.webhooksPage(workspace, endpoints, deliveries, wsPairs, session.username),
                    )
                }

                post("/settings/webhooks") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val svc = webhookService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)

                    val params = call.receiveParameters()
                    val url = params["url"]?.trim() ?: ""
                    val description = params["description"]?.trim() ?: ""
                    val events = params.getAll("events")?.toSet() ?: emptySet()

                    when (val result = svc.createEndpoint(workspace.id, url, events, description)) {
                        is com.kauth.domain.service.WebhookResult.Success -> {
                            val endpoints = svc.listEndpoints(workspace.id)
                            val deliveries = svc.recentDeliveries(workspace.id)
                            call.respondHtml(
                                HttpStatusCode.OK,
                                AdminView.webhooksPage(
                                    workspace,
                                    endpoints,
                                    deliveries,
                                    wsPairs,
                                    session.username,
                                    newSecret = result.plaintextSecret,
                                ),
                            )
                        }
                        is com.kauth.domain.service.WebhookResult.Failure -> {
                            val endpoints = svc.listEndpoints(workspace.id)
                            val deliveries = svc.recentDeliveries(workspace.id)
                            call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.webhooksPage(
                                    workspace,
                                    endpoints,
                                    deliveries,
                                    wsPairs,
                                    session.username,
                                    error = result.error,
                                ),
                            )
                        }
                    }
                }

                post("/settings/webhooks/{endpointId}/toggle") {
                    val slug =
                        call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val endpointId =
                        call.parameters["endpointId"]?.toIntOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val params = call.receiveParameters()
                    val enabled = params["enabled"] == "true"
                    webhookService?.toggleEndpoint(endpointId, workspace.id, enabled)
                    call.respondRedirect("/admin/workspaces/$slug/settings/webhooks")
                }

                post("/settings/webhooks/{endpointId}/delete") {
                    val slug =
                        call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val endpointId =
                        call.parameters["endpointId"]?.toIntOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val workspace =
                        tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                    webhookService?.deleteEndpoint(endpointId, workspace.id)
                    call.respondRedirect("/admin/workspaces/$slug/settings/webhooks")
                }
            }
        }

        // ---------------------------------------------------------------
        // Legacy & stub redirects
        // ---------------------------------------------------------------

        get("/tenants") { call.respondRedirect("/admin/workspaces", permanent = true) }
        get("/tenants/{slug}") {
            val s = call.parameters["slug"] ?: return@get call.respondRedirect("/admin/workspaces", true)
            call.respondRedirect("/admin/workspaces/$s", permanent = true)
        }

        get("/directory") {
            val first = tenantRepository.findAll().firstOrNull { !it.isMaster }
            if (first != null) {
                call.respondRedirect("/admin/workspaces/${first.slug}/users")
            } else {
                call.respondRedirect("/admin")
            }
        }

        get("/logs") { call.respondRedirect("/admin") }
        get("/security") { call.respondRedirect("/admin") }
        get("/settings") { call.respondRedirect("/admin") }
        get("/clients") { call.respondRedirect("/admin") }
        get("/users") { call.respondRedirect("/admin/directory") }
    }
}

/** Identifies an authenticated admin console session. */
data class AdminSession(
    val username: String,
)
