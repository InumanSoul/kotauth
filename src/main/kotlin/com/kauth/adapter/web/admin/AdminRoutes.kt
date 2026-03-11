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
 * Authentication uses cookie sessions, NOT JWTs.
 * JWTs are for API/OAuth consumers. Browser admin UIs use sessions because:
 *   - HttpOnly cookies can't be read by XSS
 *   - No token refresh logic in client JS needed
 *   - Logout is instant (server invalidates the session)
 *
 * The admin console only accepts credentials from the master tenant.
 * A regular user on another tenant cannot authenticate here.
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

            // Authenticate against the master tenant only
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
            // /admin/login is handled above — only check session for other admin routes
            if (!call.request.uri.startsWith("/admin/login") && call.sessions.get<AdminSession>() == null) {
                call.respondRedirect("/admin/login")
                finish()
            }
        }

        // ------------------------------------------------------------------
        // Dashboard — tenant list overview
        // ------------------------------------------------------------------

        get {
            val session = call.sessions.get<AdminSession>()!!
            val tenants = tenantRepository.findAll()
            call.respondHtml(HttpStatusCode.OK, AdminView.dashboardPage(tenants, session.username))
        }

        // ------------------------------------------------------------------
        // Tenants
        // ------------------------------------------------------------------

        route("/tenants") {

            get {
                call.respondRedirect("/admin")
            }

            // Create tenant form
            get("/new") {
                val session = call.sessions.get<AdminSession>()!!
                call.respondHtml(HttpStatusCode.OK, AdminView.createTenantPage(session.username))
            }

            // Create tenant handler
            post {
                val session = call.sessions.get<AdminSession>()!!
                val params = call.receiveParameters()
                val slug        = params["slug"]?.trim()?.lowercase() ?: ""
                val displayName = params["displayName"]?.trim() ?: ""
                val issuerUrl   = params["issuerUrl"]?.trim()?.takeIf { it.isNotBlank() }

                val prefill = TenantPrefill(
                    slug = slug,
                    displayName = displayName,
                    issuerUrl = issuerUrl ?: "",
                    registrationEnabled = params["registrationEnabled"] == "true",
                    emailVerificationRequired = params["emailVerificationRequired"] == "true"
                )

                // Validation
                val error = when {
                    slug.isBlank() -> "Slug is required."
                    !slug.matches(Regex("[a-z0-9-]+")) -> "Slug may only contain lowercase letters, numbers, and hyphens."
                    slug == Tenant.MASTER_SLUG -> "The slug 'master' is reserved."
                    displayName.isBlank() -> "Display name is required."
                    tenantRepository.existsBySlug(slug) -> "A tenant with slug '$slug' already exists."
                    else -> null
                }

                if (error != null) {
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createTenantPage(session.username, error = error, prefill = prefill)
                    )
                    return@post
                }

                tenantRepository.create(slug, displayName, issuerUrl)
                call.respondRedirect("/admin?created=$slug")
            }

            // Tenant detail
            get("/{slug}") {
                val session = call.sessions.get<AdminSession>()!!
                val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val tenant = tenantRepository.findBySlug(slug)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Tenant '$slug' not found.")
                call.respondHtml(HttpStatusCode.OK, AdminView.tenantDetailPage(tenant, session.username))
            }
        }

        // ------------------------------------------------------------------
        // Stub routes — visible in sidebar, implemented in later phases
        // ------------------------------------------------------------------

        get("/users") {
            call.respond(HttpStatusCode.NotImplemented, "User management coming in Phase 2.")
        }
        get("/clients") {
            call.respond(HttpStatusCode.NotImplemented, "Client management coming in Phase 2.")
        }
        get("/settings") {
            call.respond(HttpStatusCode.NotImplemented, "System settings coming in Phase 2.")
        }
    }
}

/**
 * Identifies an authenticated admin console session.
 * Stored server-side (SessionStorageMemory for MVP) — cleared on logout or server restart.
 */
data class AdminSession(val username: String)
