package com.kauth.adapter.web.admin

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.infrastructure.KeyProvisioningService
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
    authService           : AuthService,
    adminService          : AdminService,
    tenantRepository      : TenantRepository,
    applicationRepository : ApplicationRepository,
    userRepository        : UserRepository,
    sessionRepository     : SessionRepository,
    auditLogRepository    : AuditLogRepository,
    keyProvisioningService: KeyProvisioningService
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
            val params    = call.receiveParameters()
            val username  = params["username"]?.trim() ?: ""
            val password  = params["password"] ?: ""
            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]
            // Use authenticate() so master-tenant login events appear in the audit log
            when (authService.authenticate(Tenant.MASTER_SLUG, username, password, ipAddress, userAgent)) {
                is AuthResult.Success -> {
                    call.sessions.set(AdminSession(username = username))
                    call.respondRedirect("/admin")
                }
                is AuthResult.Failure ->
                    call.respondHtml(HttpStatusCode.Unauthorized,
                        AdminView.loginPage(error = "Invalid credentials."))
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
                call.sessions.get<AdminSession>() == null) {
                call.respondRedirect("/admin/login")
                finish()
            }
        }

        // ---------------------------------------------------------------
        // Dashboard
        // ---------------------------------------------------------------

        get {
            val session    = call.sessions.get<AdminSession>()!!
            val workspaces = tenantRepository.findAll()
            call.respondHtml(HttpStatusCode.OK,
                AdminView.dashboardPage(workspaces, session.username))
        }

        // ===============================================================
        // Workspaces
        // ===============================================================

        route("/workspaces") {

            get { call.respondRedirect("/admin") }

            get("/new") {
                val session = call.sessions.get<AdminSession>()!!
                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                call.respondHtml(HttpStatusCode.OK,
                    AdminView.createWorkspacePage(loggedInAs = session.username, allWorkspaces = wsPairs))
            }

            post {
                val session     = call.sessions.get<AdminSession>()!!
                val params      = call.receiveParameters()
                val slug        = params["slug"]?.trim()?.lowercase() ?: ""
                val displayName = params["displayName"]?.trim() ?: ""
                val issuerUrl   = params["issuerUrl"]?.trim()?.takeIf { it.isNotBlank() }
                val prefill = WorkspacePrefill(
                    slug                      = slug,
                    displayName               = displayName,
                    issuerUrl                 = issuerUrl ?: "",
                    registrationEnabled       = params["registrationEnabled"] == "true",
                    emailVerificationRequired = params["emailVerificationRequired"] == "true",
                    themeAccentColor          = params["themeAccentColor"]?.trim() ?: "#1FBCFF",
                    themeLogoUrl              = params["themeLogoUrl"]?.trim() ?: ""
                )
                val error = when {
                    slug.isBlank()                      -> "Slug is required."
                    !slug.matches(Regex("[a-z0-9-]+"))  -> "Slug may only contain lowercase letters, numbers, and hyphens."
                    slug == Tenant.MASTER_SLUG          -> "The slug 'master' is reserved."
                    displayName.isBlank()               -> "Display name is required."
                    tenantRepository.existsBySlug(slug) -> "A workspace with slug '$slug' already exists."
                    else                                -> null
                }
                if (error != null) {
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    return@post call.respondHtml(HttpStatusCode.UnprocessableEntity,
                        AdminView.createWorkspacePage(loggedInAs = session.username, allWorkspaces = wsPairs,
                            error = error, prefill = prefill))
                }
                val newTenant = tenantRepository.create(slug, displayName, issuerUrl)
                keyProvisioningService.provisionForTenant(newTenant)
                call.respondRedirect("/admin/workspaces/$slug")
            }

            // -----------------------------------------------------------
            // Per-workspace routes
            // -----------------------------------------------------------

            route("/{slug}") {

                // Workspace detail
                get {
                    val session   = call.sessions.get<AdminSession>()!!
                    val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                    val apps      = applicationRepository.findByTenantId(workspace.id)
                    call.respondHtml(HttpStatusCode.OK,
                        AdminView.workspaceDetailPage(workspace, wsPairs, apps, session.username))
                }

                // -------------------------------------------------------
                // Workspace settings
                // -------------------------------------------------------

                get("/settings") {
                    val session   = call.sessions.get<AdminSession>()!!
                    val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                    val saved     = call.request.queryParameters["saved"] == "true"
                    call.respondHtml(HttpStatusCode.OK,
                        AdminView.workspaceSettingsPage(workspace, wsPairs, session.username, saved = saved))
                }

                // -------------------------------------------------------
                // SMTP settings (Phase 3b)
                // -------------------------------------------------------

                get("/settings/smtp") {
                    val session   = call.sessions.get<AdminSession>()!!
                    val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                    val saved     = call.request.queryParameters["saved"] == "true"
                    call.respondHtml(HttpStatusCode.OK,
                        AdminView.smtpSettingsPage(workspace, wsPairs, session.username, saved = saved))
                }

                post("/settings/smtp") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug    = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val params  = call.receiveParameters()
                    when (val result = adminService.updateSmtpConfig(
                        slug            = slug,
                        smtpHost        = params["smtpHost"]?.trim()?.takeIf { it.isNotBlank() },
                        smtpPort        = params["smtpPort"]?.toIntOrNull() ?: 587,
                        smtpUsername    = params["smtpUsername"]?.trim()?.takeIf { it.isNotBlank() },
                        smtpPassword    = params["smtpPassword"]?.takeIf { it.isNotBlank() },
                        smtpFromAddress = params["smtpFromAddress"]?.trim()?.takeIf { it.isNotBlank() },
                        smtpFromName    = params["smtpFromName"]?.trim()?.takeIf { it.isNotBlank() },
                        smtpTlsEnabled  = params["smtpTlsEnabled"] == "true",
                        smtpEnabled     = params["smtpEnabled"] == "true"
                    )) {
                        is AdminResult.Success ->
                            call.respondRedirect("/admin/workspaces/$slug/settings/smtp?saved=true")
                        is AdminResult.Failure -> {
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                            call.respondHtml(HttpStatusCode.UnprocessableEntity,
                                AdminView.smtpSettingsPage(workspace, wsPairs, session.username,
                                    error = result.error.message))
                        }
                    }
                }

                post("/settings") {
                    val session = call.sessions.get<AdminSession>()!!
                    val slug    = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val params  = call.receiveParameters()
                    when (val result = adminService.updateWorkspaceSettings(
                        slug                      = slug,
                        displayName               = params["displayName"]?.trim() ?: "",
                        issuerUrl                 = params["issuerUrl"]?.trim()?.takeIf { it.isNotBlank() },
                        tokenExpirySeconds        = params["tokenExpirySeconds"]?.toLongOrNull() ?: 3600L,
                        refreshTokenExpirySeconds = params["refreshTokenExpirySeconds"]?.toLongOrNull() ?: 86400L,
                        registrationEnabled       = params["registrationEnabled"] == "true",
                        emailVerificationRequired = params["emailVerificationRequired"] == "true",
                        passwordPolicyMinLength   = params["passwordPolicyMinLength"]?.toIntOrNull() ?: 8,
                        passwordPolicyRequireSpecial = params["passwordPolicyRequireSpecial"] == "true",
                        themeAccentColor          = params["themeAccentColor"]?.trim() ?: "#1FBCFF",
                        themeLogoUrl              = params["themeLogoUrl"]?.trim()?.takeIf { it.isNotBlank() },
                        themeFaviconUrl           = params["themeFaviconUrl"]?.trim()?.takeIf { it.isNotBlank() }
                    )) {
                        is AdminResult.Success ->
                            call.respondRedirect("/admin/workspaces/$slug/settings?saved=true")
                        is AdminResult.Failure -> {
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                            call.respondHtml(HttpStatusCode.UnprocessableEntity,
                                AdminView.workspaceSettingsPage(workspace, wsPairs, session.username,
                                    error = result.error.message))
                        }
                    }
                }

                // -------------------------------------------------------
                // Applications
                // -------------------------------------------------------

                route("/applications") {

                    get("/new") {
                        val session   = call.sessions.get<AdminSession>()!!
                        val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                        call.respondHtml(HttpStatusCode.OK,
                            AdminView.createApplicationPage(workspace, wsPairs, session.username))
                    }

                    post {
                        val session   = call.sessions.get<AdminSession>()!!
                        val slug      = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                        val params    = call.receiveParameters()
                        val clientId  = params["clientId"]?.trim()?.lowercase() ?: ""
                        val name      = params["name"]?.trim() ?: ""
                        val desc      = params["description"]?.trim()?.takeIf { it.isNotBlank() }
                        val accessType = params["accessType"]?.trim() ?: "public"
                        val redirectUris = params["redirectUris"]
                            ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                        val prefill = ApplicationPrefill(clientId = clientId, name = name,
                            description = desc ?: "", accessType = accessType,
                            redirectUris = params["redirectUris"] ?: "")
                        val error = when {
                            clientId.isBlank()                                             -> "Client ID is required."
                            !clientId.matches(Regex("[a-z0-9-]+"))                         -> "Client ID may only contain lowercase letters, numbers, and hyphens."
                            applicationRepository.existsByClientId(workspace.id, clientId) -> "Client ID '$clientId' already exists."
                            name.isBlank()                                                 -> "Name is required."
                            else                                                           -> null
                        }
                        if (error != null) {
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                            return@post call.respondHtml(HttpStatusCode.UnprocessableEntity,
                                AdminView.createApplicationPage(workspace, wsPairs, session.username,
                                    error = error, prefill = prefill))
                        }
                        applicationRepository.create(workspace.id, clientId, name, desc, accessType, redirectUris)
                        call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                    }

                    route("/{clientId}") {

                        get {
                            val session   = call.sessions.get<AdminSession>()!!
                            val slug      = call.parameters["slug"]     ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val clientId  = call.parameters["clientId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val app       = applicationRepository.findByClientId(workspace.id, clientId) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                            val allApps   = applicationRepository.findByTenantId(workspace.id)
                            val newSecret = call.request.queryParameters["newSecret"]
                            call.respondHtml(HttpStatusCode.OK,
                                AdminView.applicationDetailPage(workspace, app, wsPairs, allApps, session.username, newSecret))
                        }

                        get("/edit") {
                            val session   = call.sessions.get<AdminSession>()!!
                            val slug      = call.parameters["slug"]     ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val clientId  = call.parameters["clientId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val app       = applicationRepository.findByClientId(workspace.id, clientId) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                            val allApps   = applicationRepository.findByTenantId(workspace.id)
                            call.respondHtml(HttpStatusCode.OK,
                                AdminView.editApplicationPage(workspace, app, wsPairs, allApps, session.username))
                        }

                        post("/edit") {
                            val session   = call.sessions.get<AdminSession>()!!
                            val slug      = call.parameters["slug"]     ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val clientId  = call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val app       = applicationRepository.findByClientId(workspace.id, clientId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val params    = call.receiveParameters()
                            val name      = params["name"]?.trim() ?: ""
                            val desc      = params["description"]?.trim()?.takeIf { it.isNotBlank() }
                            val accessType = params["accessType"]?.trim() ?: app.accessType.value
                            val redirectUris = params["redirectUris"]
                                ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                            when (val result = adminService.updateApplication(app.id, workspace.id, name, desc, accessType, redirectUris)) {
                                is AdminResult.Success ->
                                    call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                                is AdminResult.Failure -> {
                                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                                    val allApps = applicationRepository.findByTenantId(workspace.id)
                                    call.respondHtml(HttpStatusCode.UnprocessableEntity,
                                        AdminView.editApplicationPage(workspace, app, wsPairs, allApps,
                                            session.username, error = result.error.message))
                                }
                            }
                        }

                        post("/toggle") {
                            val slug     = call.parameters["slug"]     ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val clientId = call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val app = applicationRepository.findByClientId(workspace.id, clientId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            adminService.setApplicationEnabled(app.id, workspace.id, !app.enabled)
                            call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                        }

                        post("/regenerate-secret") {
                            val slug     = call.parameters["slug"]     ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val clientId = call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val app = applicationRepository.findByClientId(workspace.id, clientId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            when (val result = adminService.regenerateClientSecret(app.id, workspace.id)) {
                                is AdminResult.Success -> {
                                    val encoded = java.net.URLEncoder.encode(result.value, "UTF-8")
                                    call.respondRedirect("/admin/workspaces/$slug/applications/$clientId?newSecret=$encoded")
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
                        val session   = call.sessions.get<AdminSession>()!!
                        val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val search    = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                        val users     = userRepository.findByTenantId(workspace.id, search)
                        val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                        call.respondHtml(HttpStatusCode.OK,
                            AdminView.userListPage(workspace, users, wsPairs, session.username, search))
                    }

                    get("/new") {
                        val session   = call.sessions.get<AdminSession>()!!
                        val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                        call.respondHtml(HttpStatusCode.OK,
                            AdminView.createUserPage(workspace, wsPairs, session.username))
                    }

                    post {
                        val session   = call.sessions.get<AdminSession>()!!
                        val slug      = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                        val params    = call.receiveParameters()
                        val username  = params["username"]?.trim() ?: ""
                        val email     = params["email"]?.trim() ?: ""
                        val fullName  = params["fullName"]?.trim() ?: ""
                        val password  = params["password"] ?: ""
                        when (val result = adminService.createUser(workspace.id, username, email, fullName, password)) {
                            is AdminResult.Success ->
                                call.respondRedirect("/admin/workspaces/$slug/users/${result.value.id}")
                            is AdminResult.Failure -> {
                                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                                val prefill = UserPrefill(username = username, email = email, fullName = fullName)
                                call.respondHtml(HttpStatusCode.UnprocessableEntity,
                                    AdminView.createUserPage(workspace, wsPairs, session.username,
                                        error = result.error.message, prefill = prefill))
                            }
                        }
                    }

                    route("/{userId}") {

                        get {
                            val session   = call.sessions.get<AdminSession>()!!
                            val slug      = call.parameters["slug"]   ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val userId    = call.parameters["userId"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val user      = userRepository.findById(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
                            if (user.tenantId != workspace.id) return@get call.respond(HttpStatusCode.NotFound)
                            val sessions  = sessionRepository.findActiveByUser(workspace.id, userId)
                            val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                            val saved     = call.request.queryParameters["saved"] == "true"
                            call.respondHtml(HttpStatusCode.OK,
                                AdminView.userDetailPage(workspace, user, sessions, wsPairs, session.username, saved = saved))
                        }

                        post("/toggle") {
                            val slug   = call.parameters["slug"]   ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId = call.parameters["userId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val user  = userRepository.findById(userId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            if (user.tenantId != workspace.id) return@post call.respond(HttpStatusCode.NotFound)
                            adminService.setUserEnabled(userId, workspace.id, !user.enabled)
                            call.respondRedirect("/admin/workspaces/$slug/users/$userId")
                        }

                        post("/edit") {
                            val session = call.sessions.get<AdminSession>()!!
                            val slug    = call.parameters["slug"]   ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId  = call.parameters["userId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val user    = userRepository.findById(userId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            if (user.tenantId != workspace.id) return@post call.respond(HttpStatusCode.NotFound)
                            val params  = call.receiveParameters()
                            val email   = params["email"]?.trim() ?: ""
                            val fullName = params["fullName"]?.trim() ?: ""
                            when (val result = adminService.updateUser(userId, workspace.id, email, fullName)) {
                                is AdminResult.Success ->
                                    call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=true")
                                is AdminResult.Failure -> {
                                    val sessions = sessionRepository.findActiveByUser(workspace.id, userId)
                                    val wsPairs  = tenantRepository.findAll().map { it.slug to it.displayName }
                                    call.respondHtml(HttpStatusCode.UnprocessableEntity,
                                        AdminView.userDetailPage(workspace, user, sessions, wsPairs,
                                            session.username, editError = result.error.message))
                                }
                            }
                        }

                        post("/revoke-sessions") {
                            val slug   = call.parameters["slug"]   ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId = call.parameters["userId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            sessionRepository.revokeAllForUser(workspace.id, userId)
                            call.respondRedirect("/admin/workspaces/$slug/users/$userId")
                        }

                        // Phase 3b: resend email verification
                        post("/send-verification") {
                            val slug      = call.parameters["slug"]   ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId    = call.parameters["userId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val baseUrl   = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                            adminService.resendVerificationEmail(userId, workspace.id, baseUrl)
                            call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=true")
                        }

                        // Phase 3b: admin-force password reset
                        post("/admin-reset-password") {
                            val session   = call.sessions.get<AdminSession>()!!
                            val slug      = call.parameters["slug"]   ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val userId    = call.parameters["userId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val workspace = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val user      = userRepository.findById(userId) ?: return@post call.respond(HttpStatusCode.NotFound)
                            if (user.tenantId != workspace.id) return@post call.respond(HttpStatusCode.NotFound)
                            val params      = call.receiveParameters()
                            val newPassword = params["new_password"] ?: ""
                            when (val result = adminService.adminResetUserPassword(userId, workspace.id, newPassword)) {
                                is AdminResult.Success ->
                                    call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=true")
                                is AdminResult.Failure -> {
                                    val sessions = sessionRepository.findActiveByUser(workspace.id, userId)
                                    val wsPairs  = tenantRepository.findAll().map { it.slug to it.displayName }
                                    call.respondHtml(HttpStatusCode.UnprocessableEntity,
                                        AdminView.userDetailPage(workspace, user, sessions, wsPairs,
                                            session.username, editError = result.error.message))
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------------------
                // Sessions
                // -------------------------------------------------------

                get("/sessions") {
                    val session   = call.sessions.get<AdminSession>()!!
                    val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val sessions  = sessionRepository.findActiveByTenant(workspace.id)
                    val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                    call.respondHtml(HttpStatusCode.OK,
                        AdminView.activeSessionsPage(workspace, sessions, wsPairs, session.username))
                }

                post("/sessions/{sessionId}/revoke") {
                    val slug      = call.parameters["slug"]      ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val sessionId = call.parameters["sessionId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                    sessionRepository.revoke(sessionId, Instant.now())
                    call.respondRedirect("/admin/workspaces/$slug/sessions")
                }

                // -------------------------------------------------------
                // Audit log
                // -------------------------------------------------------

                get("/logs") {
                    val session      = call.sessions.get<AdminSession>()!!
                    val slug         = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val workspace    = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val page         = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val eventTypeStr = call.request.queryParameters["event"]
                    val eventType    = eventTypeStr?.let { runCatching { AuditEventType.valueOf(it) }.getOrNull() }
                    val pageSize     = 50
                    val offset       = (page - 1) * pageSize
                    val events       = auditLogRepository.findByTenant(workspace.id, eventType, limit = pageSize, offset = offset)
                    val total        = auditLogRepository.countByTenant(workspace.id, eventType)
                    val wsPairs      = tenantRepository.findAll().map { it.slug to it.displayName }
                    call.respondHtml(HttpStatusCode.OK,
                        AdminView.auditLogPage(workspace, events, wsPairs, session.username,
                            page = page, totalPages = ((total + pageSize - 1) / pageSize).toInt(),
                            eventTypeFilter = eventTypeStr))
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
            if (first != null) call.respondRedirect("/admin/workspaces/${first.slug}/users")
            else call.respondRedirect("/admin")
        }

        get("/logs")     { call.respondRedirect("/admin") }
        get("/security") { call.respond(HttpStatusCode.NotImplemented, "Security settings coming in Phase 3b.") }
        get("/settings") { call.respond(HttpStatusCode.NotImplemented, "System settings coming in Phase 3b.") }
        get("/clients")  { call.respondRedirect("/admin") }
        get("/users")    { call.respondRedirect("/admin/directory") }
    }
}

/** Identifies an authenticated admin console session. */
data class AdminSession(val username: String)
