package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.registerRoutes(
    authService: AuthService,
    tenantRepository: TenantRepository,
    registerRateLimiter: RateLimiterPort,
    identityProviderRepository: IdentityProviderRepository?,
) {
    get("/register") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else {
                emptyList()
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.registerPage(slug, theme, workspaceName, enabledProviders = enabledProviders),
        )
    }

    post("/register") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else {
                emptyList()
            }
        val ipAddress = call.request.local.remoteAddress

        val rateLimitKey = "register:$ipAddress"
        if (!registerRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.registerPage(
                    slug,
                    theme,
                    workspaceName,
                    error = "Too many registration attempts. Please wait a moment.",
                    enabledProviders = enabledProviders,
                ),
            )
        }

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
                    AuthView.registerPage(
                        slug,
                        theme,
                        workspaceName,
                        error = result.error.toMessage(),
                        prefill = prefill,
                        enabledProviders = enabledProviders,
                    ),
                )
        }
    }
}
