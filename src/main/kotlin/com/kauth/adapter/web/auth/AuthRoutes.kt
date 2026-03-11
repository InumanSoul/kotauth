package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Web adapter — HTTP routes for the auth module.
 *
 * All tenant-scoped endpoints are nested under `/t/{slug}/` (ADR-001).
 * The slug is extracted from the path, resolved to a tenant (for its theme
 * and policies), then forwarded to AuthService.
 *
 * Responsibility: parse HTTP request → call domain service → delegate to view.
 * Nothing else. No business logic, no SQL.
 */
fun Route.authRoutes(authService: AuthService, tenantRepository: TenantRepository) {

    route("/t/{slug}") {

        // ------------------------------------------------------------------
        // Login — browser UI
        // ------------------------------------------------------------------

        get("/login") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            val registered = call.request.queryParameters["registered"] == "true"
            call.respondHtml(HttpStatusCode.OK, AuthView.loginPage(slug, theme, success = registered))
        }

        post("/login") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val password = params["password"] ?: ""

            when (val result = authService.login(slug, username, password)) {
                is AuthResult.Success -> call.respond(result.value)
                is AuthResult.Failure -> call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.loginPage(slug, theme, error = result.error.toMessage())
                )
            }
        }

        // ------------------------------------------------------------------
        // Registration — browser UI
        // ------------------------------------------------------------------

        get("/register") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            call.respondHtml(HttpStatusCode.OK, AuthView.registerPage(slug, theme))
        }

        post("/register") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val email = params["email"]?.trim() ?: ""
            val fullName = params["fullName"]?.trim() ?: ""
            val password = params["password"] ?: ""
            val confirmPassword = params["confirmPassword"] ?: ""

            val prefill = RegisterPrefill(username = username, email = email, fullName = fullName)

            when (val result = authService.register(slug, username, email, fullName, password, confirmPassword)) {
                is AuthResult.Success ->
                    call.respondRedirect("/t/$slug/login?registered=true")
                is AuthResult.Failure ->
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AuthView.registerPage(slug, theme, error = result.error.toMessage(), prefill = prefill)
                    )
            }
        }

        // ------------------------------------------------------------------
        // OpenID Connect discovery + token endpoint (API consumers)
        // ------------------------------------------------------------------

        get("/.well-known/openid-configuration") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val issuer = "https://kauth.example.com/t/$slug"
            call.respond(
                mapOf(
                    "issuer"                to issuer,
                    "token_endpoint"        to "$issuer/protocol/openid-connect/token",
                    "authorization_endpoint" to "$issuer/protocol/openid-connect/auth",
                    "registration_endpoint" to "$issuer/register"
                )
            )
        }

        post("/protocol/openid-connect/token") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val password = params["password"] ?: ""

            when (val result = authService.login(slug, username, password)) {
                is AuthResult.Success -> call.respond(result.value)
                is AuthResult.Failure -> call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "invalid_client", "error_description" to result.error.toMessage())
                )
            }
        }
    }
}

/**
 * Maps domain errors to user-facing strings.
 */
private fun AuthError.toMessage(): String = when (this) {
    is AuthError.InvalidCredentials   -> "Invalid username or password."
    is AuthError.TenantNotFound       -> "Tenant not found."
    is AuthError.RegistrationDisabled -> "Registration is not enabled for this tenant."
    is AuthError.UserAlreadyExists    -> "That username is already taken."
    is AuthError.EmailAlreadyExists   -> "An account with that email already exists."
    is AuthError.WeakPassword         -> "Password must be at least $minLength characters."
    is AuthError.ValidationError      -> this.message
}
