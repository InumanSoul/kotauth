package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

/**
 * Admin console — browser-facing routes for platform administrators.
 *
 * URL structure:
 *   /admin                              — workspace list (dashboard)
 *   /admin/workspaces/new               — create workspace form
 *   /admin/workspaces                   — POST create workspace
 *   /admin/workspaces/{slug}            — workspace detail
 *   /admin/workspaces/{slug}/applications/new  — create application (Phase 2)
 *   /admin/directory                    — user directory (Phase 2)
 *   /admin/security                     — security settings (Phase 2)
 *   /admin/logs                         — audit logs (Phase 2)
 *   /admin/settings                     — system settings (Phase 2)
 *
 * Public-facing terminology: Workspace (= Tenant), Application (= Client).
 * Internal domain model retains Tenant / Client — only the HTTP/HTML layer translates.
 *
 * Authentication uses cookie sessions, NOT JWTs.
 * The admin console only accepts credentials from the master workspace (master tenant).
 */
fun Route.adminRoutes(authService: AuthService, tenantRepository: TenantRepository) {

    route("/admin") {

        // ------------------------------------------------------------------
        // Login — no session required
        // ------------------------------------------------------------------

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

            when (val result = authService.login(Tenant.MASTER_SLUG, username, password)) {
                is AuthResult.Success -> {
                    call.sessions.set(AdminSession(username = username))
                    call.respondRedirect("/admin")
                }
                is AuthResult.Failure -> {
                    call.respondHtml(
                        HttpStatusCode.Unauthorized,
                        AdminView.loginPage(error = "Invalid credentials.")
                    )
                }
            }
        }

        post("/logout") {
            call.sessions.clear<AdminSession>()
            call.respondRedirect("/admin/login")
        }

        // ------------------------------------------------------------------
        // Session guard — applied to all routes below this point
        // ------------------------------------------------------------------

        intercept(ApplicationCallPipeline.Call) {
            if (!call.request.uri.startsWith("/admin/login") && call.sessions.get<AdminSession>() == null) {
                call.respondRedirect("/admin/login")
                finish()
            }
        }

        // ------------------------------------------------------------------
        // Dashboard — workspace list
        // ------------------------------------------------------------------

        get {
            val session    = call.sessions.get<AdminSession>()!!
            val workspaces = tenantRepository.findAll()
            call.respondHtml(HttpStatusCode.OK, AdminView.dashboardPage(workspaces, session.username))
        }

        // ------------------------------------------------------------------
        // Workspaces (previously: /admin/tenants)
        // ------------------------------------------------------------------

        route("/workspaces") {

            get {
                call.respondRedirect("/admin")
            }

            // Create workspace form
            get("/new") {
                val session   = call.sessions.get<AdminSession>()!!
                val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.createWorkspacePage(
                        loggedInAs    = session.username,
                        allWorkspaces = wsPairs
                    )
                )
            }

            // Create workspace handler
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
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createWorkspacePage(
                            loggedInAs    = session.username,
                            allWorkspaces = wsPairs,
                            error         = error,
                            prefill       = prefill
                        )
                    )
                    return@post
                }

                tenantRepository.create(slug, displayName, issuerUrl)
                call.respondRedirect("/admin?created=$slug")
            }

            // Workspace detail
            get("/{slug}") {
                val session   = call.sessions.get<AdminSession>()!!
                val slug      = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = tenantRepository.findBySlug(slug)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Workspace '$slug' not found.")
                val wsPairs   = tenantRepository.findAll().map { it.slug to it.displayName }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.workspaceDetailPage(
                        workspace     = workspace,
                        allWorkspaces = wsPairs,
                        loggedInAs    = session.username
                    )
                )
            }
        }

        // ------------------------------------------------------------------
        // Legacy redirect: /admin/tenants → /admin/workspaces
        // ------------------------------------------------------------------

        get("/tenants") { call.respondRedirect("/admin/workspaces", permanent = true) }
        get("/tenants/{slug}") {
            val slug = call.parameters["slug"] ?: return@get call.respondRedirect("/admin/workspaces", permanent = true)
            call.respondRedirect("/admin/workspaces/$slug", permanent = true)
        }

        // ------------------------------------------------------------------
        // Stub routes — implemented in later phases
        // ------------------------------------------------------------------

        get("/directory") {
            call.respond(HttpStatusCode.NotImplemented, "User directory coming in Phase 2.")
        }
        get("/security") {
            call.respond(HttpStatusCode.NotImplemented, "Security settings coming in Phase 2.")
        }
        get("/logs") {
            call.respond(HttpStatusCode.NotImplemented, "Audit logs coming in Phase 2.")
        }
        get("/settings") {
            call.respond(HttpStatusCode.NotImplemented, "System settings coming in Phase 2.")
        }

        // Legacy redirect: /admin/clients → future /admin/workspaces/{slug}/applications
        get("/clients") {
            call.respondRedirect("/admin", permanent = false)
        }
        get("/users") {
            call.respondRedirect("/admin/directory", permanent = false)
        }
    }
}

/**
 * Identifies an authenticated admin console session.
 * Stored server-side (SessionStorageMemory for MVP) — cleared on logout or server restart.
 */
data class AdminSession(val username: String)
