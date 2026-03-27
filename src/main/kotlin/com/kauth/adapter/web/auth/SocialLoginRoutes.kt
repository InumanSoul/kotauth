package com.kauth.adapter.web.auth

import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SocialLoginResult
import com.kauth.domain.service.SocialLoginService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.socialLoginRoutes(
    oauthService: OAuthService,
    socialLoginService: SocialLoginService?,
    identityProviderRepository: IdentityProviderRepository?,
    encryptionService: EncryptionService,
    baseUrl: String,
) {
    get("/auth/social/{provider}/redirect") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
        val provName = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val provider =
            SocialProvider.fromValueOrNull(provName)
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported_provider"))

        if (socialLoginService == null) {
            return@get call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "social_login_not_configured"))
        }

        val oauthParams = call.request.queryParameters.toOAuthParams()

        val nonce =
            java.util.UUID
                .randomUUID()
                .toString()
        val oauthParamsB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(oauthParams.toQueryString().toByteArray(Charsets.UTF_8))
        val statePayload = "${provider.value}|$slug|$nonce|$oauthParamsB64"
        val signedState = encryptionService.signCookie(statePayload)

        when (val result = socialLoginService.buildRedirectUrl(slug, provider, signedState, baseUrl)) {
            is SocialLoginResult.Success -> call.respondRedirect(result.value)
            is SocialLoginResult.Failure -> {
                val enabledProviders =
                    if (tenant != null && identityProviderRepository != null) {
                        identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
                    } else {
                        emptyList()
                    }
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.loginPage(
                        tenantSlug = slug,
                        theme = theme,
                        workspaceName = workspaceName,
                        error = result.error.toMessage(),
                        enabledProviders = enabledProviders,
                    ),
                )
            }
            is SocialLoginResult.NeedsRegistration ->
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }

    get("/auth/social/{provider}/callback") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
        val provName = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val provider =
            SocialProvider.fromValueOrNull(provName)
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported_provider"))

        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else {
                emptyList()
            }

        if (socialLoginService == null) {
            return@get call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "social_login_not_configured"))
        }

        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val error = call.request.queryParameters["error"]

        if (!error.isNullOrBlank()) {
            call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.loginPage(
                    tenantSlug = slug,
                    theme = theme,
                    workspaceName = workspaceName,
                    error = "Login with ${provider.displayName} was cancelled or failed.",
                    enabledProviders = enabledProviders,
                ),
            )
            return@get
        }

        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_code_or_state"))
            return@get
        }

        val verifiedPayload = encryptionService.verifyCookie(state)
        if (verifiedPayload == null) {
            call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.loginPage(
                    tenantSlug = slug,
                    theme = theme,
                    workspaceName = workspaceName,
                    error = "Invalid or expired state parameter. Please try signing in again.",
                    enabledProviders = enabledProviders,
                ),
            )
            return@get
        }

        val parts = verifiedPayload.split("|")
        if (parts.size < 4 || parts[0] != provider.value || parts[1] != slug) {
            call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.loginPage(
                    tenantSlug = slug,
                    theme = theme,
                    workspaceName = workspaceName,
                    error = "State mismatch. Please try signing in again.",
                    enabledProviders = enabledProviders,
                ),
            )
            return@get
        }

        val oauthParamsRaw =
            try {
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(parts[3]),
                    Charsets.UTF_8,
                )
            } catch (_: Exception) {
                ""
            }
        val restoredParams = parseQueryStringToOAuthParams(oauthParamsRaw)

        val ipAddress = call.request.local.remoteAddress
        val userAgent = call.request.headers["User-Agent"]

        when (
            val result =
                socialLoginService.handleCallback(
                    tenantSlug = slug,
                    provider = provider,
                    code = code,
                    baseUrl = baseUrl,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                )
        ) {
            is SocialLoginResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.loginPage(
                        tenantSlug = slug,
                        theme = theme,
                        workspaceName = workspaceName,
                        error = result.error.toMessage(),
                        enabledProviders = enabledProviders,
                    ),
                )
            }
            is SocialLoginResult.NeedsRegistration -> {
                val pending = result.data
                val cookieVal =
                    encryptionService.signCookie(
                        buildSocialPendingPayload(pending, slug, oauthParamsRaw),
                    )
                call.response.cookies.append(
                    name = "KOTAUTH_SOCIAL_PENDING",
                    value = cookieVal,
                    maxAge = 600L,
                    httpOnly = true,
                    path = "/t/$slug/auth/social",
                )
                call.respondRedirect("/t/$slug/auth/social/complete-registration")
            }
            is SocialLoginResult.Success -> {
                val loginSuccess = result.value
                if (restoredParams.isOAuthFlow) {
                    val clientId = restoredParams.clientId ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val redirectUri =
                        restoredParams.redirectUri ?: return@get call.respond(HttpStatusCode.BadRequest)
                    when (
                        val codeResult =
                            oauthService.issueAuthorizationCode(
                                tenantSlug = slug,
                                userId = loginSuccess.user.id!!,
                                clientId = clientId,
                                redirectUri = redirectUri,
                                scopes = restoredParams.scope ?: "openid",
                                codeChallenge = restoredParams.codeChallenge,
                                codeChallengeMethod = restoredParams.codeChallengeMethod,
                                nonce = restoredParams.nonce,
                                state = restoredParams.state,
                                ipAddress = ipAddress,
                            )
                    ) {
                        is OAuthResult.Success -> {
                            val authCode = codeResult.value.code
                            val stateParam = restoredParams.state
                            val redirect =
                                buildString {
                                    append(redirectUri)
                                    append("?code=").append(authCode)
                                    if (!stateParam.isNullOrBlank()) append("&state=").append(stateParam)
                                }
                            call.respondRedirect(redirect)
                        }
                        is OAuthResult.Failure ->
                            call.respondHtml(
                                HttpStatusCode.BadRequest,
                                AuthView.loginPage(
                                    tenantSlug = slug,
                                    theme = theme,
                                    workspaceName = workspaceName,
                                    error = codeResult.error.toDescription(),
                                    enabledProviders = enabledProviders,
                                ),
                            )
                    }
                } else {
                    call.respond(loginSuccess.tokens)
                }
            }
        }
    }

    // Social registration completion
    get("/auth/social/complete-registration") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant ?: return@get call.respond(HttpStatusCode.NotFound)
        val theme = tenant.theme
        val workspaceName = tenant.displayName

        val rawCookie = call.request.cookies["KOTAUTH_SOCIAL_PENDING"]
        val pending = parseSocialPendingCookie(rawCookie, encryptionService)

        if (pending == null) {
            return@get call.respondRedirect(
                "/t/$slug/authorize?error=${encodeParam("Session expired. Please sign in again.")}",
            )
        }

        val suggestedUsername =
            pending.email
                .substringBefore("@")
                .replace(Regex("[^a-zA-Z0-9_]"), "")
                .lowercase()
                .take(32)
                .ifBlank { "user" }

        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.socialRegistrationPage(
                tenantSlug = slug,
                theme = theme,
                workspaceName = workspaceName,
                providerName = pending.provider.displayName,
                email = pending.email,
                prefillUsername = suggestedUsername,
                prefillFullName = pending.name ?: "",
            ),
        )
    }

    post("/auth/social/complete-registration") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant ?: return@post call.respond(HttpStatusCode.NotFound)
        val theme = tenant.theme
        val workspaceName = tenant.displayName

        val rawCookie = call.request.cookies["KOTAUTH_SOCIAL_PENDING"]
        val pending = parseSocialPendingCookie(rawCookie, encryptionService)

        if (pending == null) {
            return@post call.respondRedirect(
                "/t/$slug/authorize?error=${encodeParam("Session expired. Please sign in again.")}",
            )
        }

        if (socialLoginService == null) {
            return@post call.respond(HttpStatusCode.NotImplemented)
        }

        val params = call.receiveParameters()
        val chosenUsername = params["username"]?.trim() ?: ""
        val chosenFullName = params["full_name"]?.trim()
        val ipAddress = call.request.local.remoteAddress
        val userAgent = call.request.headers["User-Agent"]

        when (
            val result =
                socialLoginService.completeSocialRegistration(
                    tenantSlug = slug,
                    provider = pending.provider,
                    providerUserId = pending.providerUserId,
                    email = pending.email,
                    providerName = chosenFullName?.ifBlank { null } ?: pending.name,
                    avatarUrl = pending.avatarUrl,
                    emailVerified = pending.emailVerified,
                    chosenUsername = chosenUsername,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                )
        ) {
            is SocialLoginResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AuthView.socialRegistrationPage(
                        tenantSlug = slug,
                        theme = theme,
                        workspaceName = workspaceName,
                        providerName = pending.provider.displayName,
                        email = pending.email,
                        prefillUsername = chosenUsername,
                        prefillFullName = chosenFullName ?: pending.name ?: "",
                        error = result.error.toMessage(),
                    ),
                )
            }
            is SocialLoginResult.NeedsRegistration -> {
                call.respondHtml(
                    HttpStatusCode.InternalServerError,
                    AuthView.socialRegistrationPage(
                        tenantSlug = slug,
                        theme = theme,
                        workspaceName = workspaceName,
                        providerName = pending.provider.displayName,
                        email = pending.email,
                        error = "An unexpected error occurred. Please try again.",
                    ),
                )
            }
            is SocialLoginResult.Success -> {
                call.response.cookies.append(
                    name = "KOTAUTH_SOCIAL_PENDING",
                    value = "",
                    maxAge = 0L,
                    httpOnly = true,
                    path = "/t/$slug/auth/social",
                )
                val loginSuccess = result.value
                val restoredParams = parseQueryStringToOAuthParams(pending.oauthParamsRaw)

                if (restoredParams.isOAuthFlow) {
                    val clientId = restoredParams.clientId ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val redirectUri =
                        restoredParams.redirectUri ?: return@post call.respond(HttpStatusCode.BadRequest)
                    when (
                        val codeResult =
                            oauthService.issueAuthorizationCode(
                                tenantSlug = slug,
                                userId = loginSuccess.user.id!!,
                                clientId = clientId,
                                redirectUri = redirectUri,
                                scopes = restoredParams.scope ?: "openid",
                                codeChallenge = restoredParams.codeChallenge,
                                codeChallengeMethod = restoredParams.codeChallengeMethod,
                                nonce = restoredParams.nonce,
                                state = restoredParams.state,
                                ipAddress = ipAddress,
                            )
                    ) {
                        is OAuthResult.Success -> {
                            val authCode = codeResult.value.code
                            val stateParam = restoredParams.state
                            val redirect =
                                buildString {
                                    append(redirectUri)
                                    append("?code=").append(authCode)
                                    if (!stateParam.isNullOrBlank()) append("&state=").append(stateParam)
                                }
                            call.respondRedirect(redirect)
                        }
                        is OAuthResult.Failure ->
                            call.respondHtml(
                                HttpStatusCode.BadRequest,
                                AuthView.socialRegistrationPage(
                                    tenantSlug = slug,
                                    theme = theme,
                                    workspaceName = workspaceName,
                                    providerName = pending.provider.displayName,
                                    email = pending.email,
                                    error = codeResult.error.toDescription(),
                                ),
                            )
                    }
                } else {
                    call.respondRedirect("/t/$slug/account/login")
                }
            }
        }
    }
}
