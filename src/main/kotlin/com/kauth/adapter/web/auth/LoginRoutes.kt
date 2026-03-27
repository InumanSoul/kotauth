package com.kauth.adapter.web.auth

import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.loginRoutes(
    authService: AuthService,
    loginRateLimiter: RateLimiterPort,
    identityProviderRepository: IdentityProviderRepository?,
) {
    // Plain login page — for direct (non-OAuth) logins and admin bypass.
    // OAuth flows enter via GET /authorize, which sets the auth context cookie
    // before rendering this same view.
    get("/login") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
        val registered = call.request.queryParameters["registered"] == "true"
        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else {
                emptyList()
            }

        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.loginPage(
                tenantSlug = slug,
                theme = theme,
                workspaceName = workspaceName,
                success = registered,
                enabledProviders = enabledProviders,
                registrationEnabled = tenant?.registrationEnabled ?: true,
            ),
        )
    }

    // Direct (non-OAuth) login — issues tokens directly in the response body.
    // OAuth logins POST to /authorize instead; this handler is kept for API clients
    // and the admin bypass flow that doesn't use PKCE.
    post("/login") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else {
                emptyList()
            }
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val password = params["password"] ?: ""
        val ipAddress = call.request.local.remoteAddress

        val rateLimitKey = "login:$ipAddress:$slug"
        if (!loginRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.loginPage(
                    slug,
                    theme,
                    workspaceName,
                    error = "Too many login attempts. Please wait a moment and try again.",
                    enabledProviders = enabledProviders,
                    registrationEnabled = tenant?.registrationEnabled ?: true,
                ),
            )
        }

        val userAgent = call.request.headers["User-Agent"]
        when (val tokenResult = authService.login(slug, username, password, ipAddress, userAgent)) {
            is AuthResult.Success -> call.respond(tokenResult.value)
            is AuthResult.Failure ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "invalid_credentials"),
                )
        }
    }
}
