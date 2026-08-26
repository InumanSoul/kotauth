package com.kauth.adapter.web.auth

import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
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
    credentialFlowService: CredentialFlowService,
    registerRateLimiter: RateLimiterPort,
    identityProviderRepository: IdentityProviderRepository?,
    baseUrl: String,
    encryptionService: EncryptionService,
) {
    get("/register") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).asLoginProviders()
            } else {
                emptyList()
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.registerPage(
                tenantSlug = slug,
                ctx = ctx.viewContext,
                enabledProviders = enabledProviders,
                passwordPolicy = tenant?.securityConfig ?: SecurityConfig(),
                passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
            ),
        )
    }

    post("/register") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        val passwordlessTenant = tenant?.securityConfig?.passwordLoginEnabled == false
        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).asLoginProviders()
            } else {
                emptyList()
            }
        val ipAddress = call.request.local.remoteAddress

        val rateLimitKey = "register:$ipAddress:$slug"
        if (!registerRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.registerPage(
                    tenantSlug = slug,
                    ctx = ctx.viewContext,
                    error = "Too many registration attempts. Please wait a moment.",
                    enabledProviders = enabledProviders,
                    passwordPolicy = tenant?.securityConfig ?: SecurityConfig(),
                    passwordLoginEnabled = !passwordlessTenant,
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

        val originatingClientId = call.getAuthContext(encryptionService)?.clientId

        when (
            val result =
                authService.register(
                    tenantSlug = slug,
                    username = username,
                    email = email,
                    fullName = fullName,
                    rawPassword = password,
                    confirmPassword = confirmPassword,
                    baseUrl = baseUrl,
                    originatingClientId = originatingClientId,
                )
        ) {
            is AuthResult.Success -> {
                if (passwordlessTenant) {
                    // No password the user knows — issue a magic-link so they can complete
                    // first sign-in. Same enumeration-safe redirect as the normal flow.
                    credentialFlowService.initiateMagicLink(
                        email = email,
                        tenantSlug = slug,
                        baseUrl = baseUrl,
                        ipAddress = ipAddress,
                    )
                    call.respondRedirect("/t/$slug/magic-link?sent=true")
                } else {
                    val hasOAuthContext = call.getAuthContext(encryptionService) != null
                    val redirect =
                        if (hasOAuthContext) "/t/$slug/authorize?registered=true" else "/t/$slug/account/login"
                    call.respondRedirect(redirect)
                }
            }
            is AuthResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AuthView.registerPage(
                        tenantSlug = slug,
                        ctx = ctx.viewContext,
                        error = result.error.toMessage(),
                        prefill = prefill,
                        enabledProviders = enabledProviders,
                        passwordPolicy = tenant?.securityConfig ?: SecurityConfig(),
                        passwordLoginEnabled = !passwordlessTenant,
                    ),
                )
        }
    }
}
