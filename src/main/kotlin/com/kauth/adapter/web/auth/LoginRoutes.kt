package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.PortalClientProvisioning
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.loginRoutes(
    authService: AuthService,
    oauthService: OAuthService,
    tenantRepository: TenantRepository,
    loginRateLimiter: RateLimiterPort,
    mfaService: MfaService?,
    roleRepository: RoleRepository?,
    identityProviderRepository: IdentityProviderRepository?,
    encryptionService: EncryptionService,
) {
    get("/login") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val registered = call.request.queryParameters["registered"] == "true"
        val oauthParams = call.request.queryParameters.toOAuthParams()
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
                oauthParams = oauthParams,
                enabledProviders = enabledProviders,
            ),
        )
    }

    post("/login") {
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
        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val password = params["password"] ?: ""
        val ipAddress = call.request.local.remoteAddress
        val oauthParams = params.toOAuthParams()

        val rateLimitKey = "login:$ipAddress"
        if (!loginRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.loginPage(
                    slug,
                    theme,
                    workspaceName,
                    error = "Too many login attempts. Please wait a moment and try again.",
                    oauthParams = oauthParams,
                    enabledProviders = enabledProviders,
                ),
            )
        }

        val userAgent = call.request.headers["User-Agent"]
        when (val result = authService.authenticate(slug, username, password, ipAddress, userAgent)) {
            is AuthResult.Failure -> {
                if (result.error is AuthError.PasswordExpired) {
                    call.respondRedirect("/t/$slug/forgot-password?reason=expired")
                } else {
                    call.respondHtml(
                        HttpStatusCode.Unauthorized,
                        AuthView.loginPage(
                            slug,
                            theme,
                            workspaceName,
                            error = result.error.toMessage(),
                            oauthParams = oauthParams,
                            enabledProviders = enabledProviders,
                        ),
                    )
                }
            }
            is AuthResult.Success -> {
                val user = result.value

                // Enforce MFA enrollment before challenge check (skip for portal logins)
                val isPortalLogin =
                    oauthParams.isOAuthFlow &&
                        oauthParams.clientId == PortalClientProvisioning.PORTAL_CLIENT_ID
                if (mfaService != null && tenant != null && !isPortalLogin) {
                    val mfaPolicy = tenant.mfaPolicy
                    if (mfaPolicy != "optional") {
                        val userRoles =
                            if (mfaPolicy == "required_admins" && roleRepository != null) {
                                roleRepository.resolveEffectiveRoles(user.id!!, tenant.id)
                            } else {
                                emptyList()
                            }

                        if (mfaService.isMfaRequired(user, mfaPolicy, userRoles) &&
                            !mfaService.shouldChallengeMfa(user.id!!)
                        ) {
                            return@post call.respondHtml(
                                HttpStatusCode.Forbidden,
                                AuthView.loginPage(
                                    tenantSlug = slug,
                                    theme = theme,
                                    workspaceName = workspaceName,
                                    error =
                                        "Multi-factor authentication is required for your account. " +
                                            "Please sign in to the user portal and enable MFA under Security settings.",
                                    oauthParams = oauthParams,
                                    enabledProviders = enabledProviders,
                                ),
                            )
                        }
                    }
                }

                // MFA challenge redirect
                if (mfaService != null && mfaService.shouldChallengeMfa(user.id!!)) {
                    val mfaPending = "${user.id}|$slug|${System.currentTimeMillis()}"
                    call.response.cookies.append(
                        name = "KOTAUTH_MFA_PENDING",
                        value = encryptionService.signCookie(mfaPending),
                        maxAge = 300L,
                        httpOnly = true,
                        path = "/t/$slug",
                    )
                    val queryString = if (oauthParams.isOAuthFlow) oauthParams.toQueryString() else ""
                    call.respondRedirect("/t/$slug/mfa-challenge$queryString")
                    return@post
                }

                if (oauthParams.isOAuthFlow) {
                    val clientId =
                        oauthParams.clientId
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing client_id")
                    val redirectUri =
                        oauthParams.redirectUri
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing redirect_uri")

                    when (
                        val codeResult =
                            oauthService.issueAuthorizationCode(
                                tenantSlug = slug,
                                userId = user.id!!,
                                clientId = clientId,
                                redirectUri = redirectUri,
                                scopes = oauthParams.scope ?: "openid",
                                codeChallenge = oauthParams.codeChallenge,
                                codeChallengeMethod = oauthParams.codeChallengeMethod,
                                nonce = oauthParams.nonce,
                                state = oauthParams.state,
                                ipAddress = ipAddress,
                            )
                    ) {
                        is OAuthResult.Success -> {
                            val code = codeResult.value.code
                            val state = oauthParams.state
                            val redirect =
                                buildString {
                                    append(redirectUri)
                                    append("?code=").append(code)
                                    if (!state.isNullOrBlank()) append("&state=").append(state)
                                }
                            call.respondRedirect(redirect)
                        }
                        is OAuthResult.Failure -> {
                            call.respondHtml(
                                HttpStatusCode.BadRequest,
                                AuthView.loginPage(
                                    slug,
                                    theme,
                                    workspaceName,
                                    error = codeResult.error.toDescription(),
                                    oauthParams = oauthParams,
                                    enabledProviders = enabledProviders,
                                ),
                            )
                        }
                    }
                } else {
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
        }
    }
}
