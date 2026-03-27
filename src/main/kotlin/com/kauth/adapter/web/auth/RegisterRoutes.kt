package com.kauth.adapter.web.auth

import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.registerRoutes(
    authService: AuthService,
    registerRateLimiter: RateLimiterPort,
    identityProviderRepository: IdentityProviderRepository?,
    baseUrl: String,
    encryptionService: EncryptionService,
) {
    get("/register") {
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
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.registerPage(slug, theme, workspaceName, enabledProviders = enabledProviders),
        )
    }

    post("/register") {
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
        val ipAddress = call.request.local.remoteAddress

        val rateLimitKey = "register:$ipAddress:$slug"
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

        when (val result = authService.register(slug, username, email, fullName, password, confirmPassword, baseUrl)) {
            is AuthResult.Success -> {
                // If an OAuth flow is active (auth context cookie), return to it.
                // Otherwise redirect to the portal login which starts a proper OAuth flow.
                val hasOAuthContext = call.getAuthContext(encryptionService) != null
                val redirect =
                    if (hasOAuthContext) "/t/$slug/authorize?registered=true" else "/t/$slug/account/login"
                call.respondRedirect(redirect)
            }
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
