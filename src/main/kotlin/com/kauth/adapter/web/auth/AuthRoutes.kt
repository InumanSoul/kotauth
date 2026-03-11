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
 * Responsibility: parse the HTTP request → call the domain service → delegate to the view.
 * Nothing else. No business logic, no HTML generation, no SQL.
 *
 * This is the Ktor extension function pattern: install via `routing { authRoutes(service) }`.
 * Adding an admin module later = create adminRoutes() in its own file, same pattern.
 */
fun Route.authRoutes(authService: AuthService) {

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    get("/login") {
        val registered = call.request.queryParameters["registered"] == "true"
        call.respondHtml(HttpStatusCode.OK, AuthView.loginPage(success = registered))
    }

    post("/login") {
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val password = params["password"] ?: ""

        when (val result = authService.login(username, password)) {
            is AuthResult.Success -> call.respond(result.value)
            is AuthResult.Failure -> call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to result.error.toMessage())
            )
        }
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    get("/register") {
        call.respondHtml(HttpStatusCode.OK, AuthView.registerPage())
    }

    post("/register") {
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val email = params["email"]?.trim() ?: ""
        val fullName = params["fullName"]?.trim() ?: ""
        val password = params["password"] ?: ""
        val confirmPassword = params["confirmPassword"] ?: ""

        val prefill = RegisterPrefill(username = username, email = email, fullName = fullName)

        when (val result = authService.register(username, email, fullName, password, confirmPassword)) {
            is AuthResult.Success -> {
                // Redirect to login with a success flag — avoids form resubmission on refresh
                call.respondRedirect("/login?registered=true")
            }
            is AuthResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AuthView.registerPage(error = result.error.toMessage(), prefill = prefill)
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // OpenID Connect discovery + token endpoint (API consumers)
    // ------------------------------------------------------------------

    get("/.well-known/openid-configuration") {
        val issuer = "https://kauth.example.com"
        call.respond(
            mapOf(
                "issuer" to issuer,
                "token_endpoint" to "$issuer/token",
                "registration_endpoint" to "$issuer/register"
            )
        )
    }

    post("/token") {
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val password = params["password"] ?: ""

        when (val result = authService.login(username, password)) {
            is AuthResult.Success -> call.respond(result.value)
            is AuthResult.Failure -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

/**
 * Maps domain errors to user-facing messages.
 * Lives in the web adapter — the domain doesn't know what words to show a browser user.
 */
private fun AuthError.toMessage(): String = when (this) {
    is AuthError.InvalidCredentials -> "Invalid username or password."
    is AuthError.UserAlreadyExists -> "That username is already taken."
    is AuthError.EmailAlreadyExists -> "An account with that email already exists."
    is AuthError.WeakPassword -> "Password must be at least 8 characters."
    is AuthError.ValidationError -> this.message
}
