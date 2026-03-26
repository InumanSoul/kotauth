package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.WebhookService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.PortalClientProvisioning
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

fun Route.adminRoutes(
    authService: AuthService,
    adminService: AdminService,
    roleGroupService: RoleGroupService,
    appInfo: AppInfo,
    tenantRepository: TenantRepository,
    applicationRepository: ApplicationRepository,
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
    keyProvisioningService: KeyProvisioningService,
    mfaRepository: MfaRepository? = null,
    portalClientProvisioning: PortalClientProvisioning? = null,
    identityProviderRepository: IdentityProviderRepository? = null,
    apiKeyService: ApiKeyService? = null,
    webhookService: WebhookService? = null,
    encryptionService: EncryptionService,
) {
    AdminView.setShellAppInfo(appInfo)

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
        // Smart redirect: last workspace → first workspace → create
        // ---------------------------------------------------------------

        get {
            val workspaces = tenantRepository.findAll().filter { !it.isMaster }
            if (workspaces.isEmpty()) {
                call.respondRedirect("/admin/workspaces/new")
            } else if (workspaces.size == 1) {
                call.respondRedirect("/admin/workspaces/${workspaces.first().slug}")
            } else {
                val fallback = workspaces.first().slug
                call.respondHtml(HttpStatusCode.OK, AdminView.workspaceRedirector(fallback))
            }
        }

        // ===============================================================
        // Workspaces
        // ===============================================================

        route("/workspaces") {
            get {
                val session = call.sessions.get<AdminSession>()!!
                val workspaces = tenantRepository.findAll()
                val wsPairs = workspaces.map { it.slug to it.displayName }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.workspaceListPage(workspaces, wsPairs, session.username),
                )
            }

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
                portalClientProvisioning?.provisionRedirectUris()
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
                // Resolve workspace + sidebar pairs once per request
                intercept(ApplicationCallPipeline.Call) {
                    val slug =
                        call.parameters["slug"]
                            ?: return@intercept call.respond(HttpStatusCode.BadRequest).also { finish() }
                    val workspace =
                        tenantRepository.findBySlug(slug)
                            ?: return@intercept call.respond(HttpStatusCode.NotFound).also { finish() }
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    call.attributes.put(WorkspaceAttr, workspace)
                    call.attributes.put(WsPairsAttr, wsPairs)
                }

                get {
                    val session = call.sessions.get<AdminSession>()!!
                    val workspace = call.attributes[WorkspaceAttr]
                    val wsPairs = call.attributes[WsPairsAttr]
                    val apps = applicationRepository.findByTenantId(workspace.id)
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.workspaceDetailPage(workspace, wsPairs, apps, session.username),
                    )
                }

                adminSettingsRoutes(
                    adminService = adminService,
                    userRepository = userRepository,
                    identityProviderRepository = identityProviderRepository,
                    mfaRepository = mfaRepository,
                    encryptionService = encryptionService,
                )

                adminApplicationRoutes(
                    adminService = adminService,
                    applicationRepository = applicationRepository,
                )

                adminUserRoutes(
                    adminService = adminService,
                    roleGroupService = roleGroupService,
                    userRepository = userRepository,
                    sessionRepository = sessionRepository,
                )

                adminSessionAuditRoutes(
                    sessionRepository = sessionRepository,
                    auditLogRepository = auditLogRepository,
                )

                adminRbacRoutes(
                    roleGroupService = roleGroupService,
                    applicationRepository = applicationRepository,
                    userRepository = userRepository,
                )

                adminApiKeyRoutes(
                    apiKeyService = apiKeyService,
                )

                adminWebhookRoutes(
                    webhookService = webhookService,
                )
            }
        }
    }
}

/** Identifies an authenticated admin console session. */
data class AdminSession(
    val username: String,
)
