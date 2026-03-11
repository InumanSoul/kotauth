package com.kauth.adapter.web.auth

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
 * The slug is extracted from the path and forwarded to AuthService, which
 * resolves the tenant and enforces its policies.
 *
 * Responsibility: parse HTTP request → call domain service → delegate to view.
 * Nothing else. No business logic, no HTML, no SQL.
 */
fun Route.authRoutes(authService: AuthService) {

    route("/t/{slug}") {

        // ------------------------------------------------------------------
        // Login — browser UI
        // ------------------------------------------------------------------

        get("/login") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val registered = call.request.queryParameters["registered"] == "true"
            call.respondHtml(HttpStatusCode.OK, AuthView.loginPage(tenantSlug = slug, success = registered))
        }

        post("/login") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val password = params["password"] ?: ""

            when (val result = authService.login(slug, username, password)) {
                is AuthResult.Success -> call.respond(result.value)
                is AuthResult.Failure -> call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to result.error.toMessage())
                )
            }
        }

        // ------------------------------------------------------------------
        // Registration — browser UI
        // ------------------------------------------------------------------

        get("/register") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respondHtml(HttpStatusCode.OK, AuthView.registerPage(tenantSlug = slug))
        }

        post("/register") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val email = params["email"]?.trim() ?: ""
            val fullName = params["fullName"]?.trim() ?: ""
            val password = params["password"] ?: ""
            val confirmPassword = params["confirmPassword"] ?: ""

            val prefill = RegisterPrefill(username = username, email = email, fullName = fullName)

            when (val result = authService.register(slug, username, email, fullName, password, confirmPassword)) {
                is AuthResult.Success -> {
                    // Redirect to login with a success flag — prevents form resubmission on refresh
                    call.respondRedirect("/t/$slug/login?registered=true")
                }
                is AuthResult.Failure -> {
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AuthView.registerPage(
                            tenantSlug = slug,
                            error = result.error.toMessage(),
                            prefill = prefill
                        )
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // OpenID Connect — API consumers
        // ------------------------------------------------------------------

        get("/.well-known/openid-configuration") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            // Issuer URL will eventually come from the tenant record (Phase 2)
            val issuer = "https://kauth.example.com/t/$slug"
            call.respond(
                mapOf(
                    "issuer" to issuer,
                    "token_endpoint" to "$issuer/protocol/openid-connect/token",
                    "authorization_endpoint" to "$issuer/protocol/openid-connect/auth",
                    "registration_endpoint" to "$issuer/register"
                )
            )
        }

        /*
         * Token endpoint — accepts Resource Owner Password Credentials for now.
         * This will be replaced by the full Authorization Code + PKCE flow in Phase 2.
         */
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
 * Lives in the web adapter — the domain doesn't know what words to show a browser user.
 */
private fun AuthError.toMessage(): String = when (this) {
    is AuthError.InvalidCredentials  -> "Invalid username or password."
    is AuthError.TenantNotFound      -> "Tenant not found."
    is AuthError.RegistrationDisabled -> "Registration is not enabled for this tenant."
    is AuthError.UserAlreadyExists   -> "That username is already taken."
    is AuthError.EmailAlreadyExists  -> "An account with that email already exists."
    is AuthError.WeakPassword        -> "Password must be at least $minLength characters."
    is AuthError.ValidationError     -> this.message
}
