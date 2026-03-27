package com.kauth.adapter.web.auth

import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.IntrospectionResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.PortalClientProvisioning
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.*

internal fun Route.oauthProtocolRoutes(
    oauthService: OAuthService,
    identityProviderRepository: IdentityProviderRepository?,
    tokenRateLimiter: RateLimiterPort,
    authService: AuthService,
    mfaService: MfaService?,
    roleRepository: RoleRepository?,
    encryptionService: EncryptionService,
    loginRateLimiter: RateLimiterPort,
    baseUrl: String = "",
) {
    get("/.well-known/openid-configuration") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant =
            ctx.tenant
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "tenant_not_found"))

        val openidBaseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
        val issuer = tenant.issuerUrl ?: "$openidBaseUrl/t/$slug"

        call.respond(
            buildJsonObject {
                put("issuer", issuer)
                put("authorization_endpoint", "$issuer/authorize")
                put("token_endpoint", "$issuer/protocol/openid-connect/token")
                put("userinfo_endpoint", "$issuer/protocol/openid-connect/userinfo")
                put("jwks_uri", "$issuer/protocol/openid-connect/certs")
                put("end_session_endpoint", "$issuer/protocol/openid-connect/logout")
                put("revocation_endpoint", "$issuer/protocol/openid-connect/revoke")
                put("introspection_endpoint", "$issuer/protocol/openid-connect/introspect")
                put("response_types_supported", buildJsonArray { add("code") })
                put(
                    "grant_types_supported",
                    buildJsonArray {
                        add("authorization_code")
                        add("client_credentials")
                        add("refresh_token")
                    },
                )
                put("subject_types_supported", buildJsonArray { add("public") })
                put("id_token_signing_alg_values_supported", buildJsonArray { add("RS256") })
                put(
                    "token_endpoint_auth_methods_supported",
                    buildJsonArray {
                        add("client_secret_post")
                        add("client_secret_basic")
                    },
                )
                put(
                    "scopes_supported",
                    buildJsonArray {
                        add("openid")
                        add("profile")
                        add("email")
                    },
                )
                put(
                    "claims_supported",
                    buildJsonArray {
                        add("sub")
                        add("iss")
                        add("aud")
                        add("exp")
                        add("iat")
                        add("email")
                        add("email_verified")
                        add("name")
                        add("preferred_username")
                    },
                )
                put("code_challenge_methods_supported", buildJsonArray { add("S256") })
            },
        )
    }

    get("/protocol/openid-connect/certs") {
        val ctx = call.attributes[AuthTenantAttr]
        val tenant =
            ctx.tenant
                ?: return@get call.respond(HttpStatusCode.NotFound)

        val jwks = oauthService.getJwks(tenant.id)
        call.respond(mapOf("keys" to jwks))
    }

    // Legacy endpoint — redirects to the canonical /authorize path.
    // Kept for backwards compatibility with existing OIDC clients that
    // have the old URL hardcoded before their discovery doc refreshes.
    get("/protocol/openid-connect/auth") {
        val slug = call.attributes[AuthTenantAttr].slug
        val queryString = call.request.queryString()
        call.respondRedirect("/t/$slug/authorize?$queryString")
    }

    get("/authorize") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val q = call.request.queryParameters

        val responseType = q["response_type"] ?: ""
        val clientId = q["client_id"] ?: ""
        val redirectUri = q["redirect_uri"] ?: ""
        val scope = q["scope"] ?: "openid"
        val state = q["state"]
        val nonce = q["nonce"]
        val codeChallenge = q["code_challenge"]
        val codeChallengeMethod = q["code_challenge_method"]

        if (responseType != "code") {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "unsupported_response_type",
                    "error_description" to "Only 'code' response_type is supported",
                ),
            )
            return@get
        }

        if (clientId.isBlank() || redirectUri.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "invalid_request",
                    "error_description" to "client_id and redirect_uri are required",
                ),
            )
            return@get
        }

        val tenant =
            ctx.tenant
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "tenant_not_found"))

        val oauthParams =
            AuthView.OAuthParams(
                responseType = responseType,
                clientId = clientId,
                redirectUri = redirectUri,
                scope = scope,
                state = state,
                codeChallenge = codeChallenge,
                codeChallengeMethod = codeChallengeMethod,
                nonce = nonce,
            )

        call.setAuthContextCookie(oauthParams, slug, encryptionService, baseUrl.startsWith("https"))

        val enabledProviders =
            identityProviderRepository
                ?.findEnabledByTenant(tenant.id)
                ?.map { it.provider } ?: emptyList()

        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.loginPage(
                tenantSlug = slug,
                theme = tenant.theme,
                workspaceName = tenant.displayName,
                oauthParams = oauthParams,
                enabledProviders = enabledProviders,
                registrationEnabled = tenant.registrationEnabled,
            ),
        )
    }

    post("/authorize") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
        val ipAddress = call.request.local.remoteAddress
        val userAgent = call.request.headers["User-Agent"]

        val enabledProviders =
            if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else {
                emptyList()
            }

        // Rate limit login attempts per IP + tenant
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

        // OAuth context comes from the signed cookie, not form fields.
        // If the cookie is absent or expired, the session has timed out.
        val oauthParams = call.getAuthContext(encryptionService)
        if (oauthParams == null) {
            call.respondRedirect("/t/$slug/authorize?error=session_expired")
            return@post
        }

        val params = call.receiveParameters()
        val username = params["username"]?.trim() ?: ""
        val password = params["password"] ?: ""

        when (val result = authService.authenticate(slug, username, password, ipAddress, userAgent, baseUrl)) {
            is AuthResult.Failure -> {
                if (result.error is AuthError.PasswordExpired) {
                    call.respondRedirect("/t/$slug/forgot-password?reason=expired")
                } else {
                    call.respondHtml(
                        HttpStatusCode.Unauthorized,
                        AuthView.loginPage(
                            tenantSlug = slug,
                            theme = theme,
                            workspaceName = workspaceName,
                            error = result.error.toMessage(),
                            oauthParams = oauthParams,
                            enabledProviders = enabledProviders,
                            registrationEnabled = tenant?.registrationEnabled ?: true,
                        ),
                    )
                }
            }
            is AuthResult.Success -> {
                val user = result.value

                // Enforce MFA enrollment policy before issuing a challenge (skip for portal logins)
                val isPortalLogin = oauthParams.clientId == PortalClientProvisioning.PORTAL_CLIENT_ID
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
                                    registrationEnabled = tenant.registrationEnabled,
                                ),
                            )
                        }
                    }
                }

                // MFA challenge — auth context cookie remains active across the redirect
                if (mfaService != null && mfaService.shouldChallengeMfa(user.id!!)) {
                    val mfaPending = "${user.id.value}|$slug|${System.currentTimeMillis()}"
                    call.response.cookies.append(
                        name = "KOTAUTH_MFA_PENDING",
                        value = encryptionService.signCookie(mfaPending),
                        maxAge = 300L,
                        httpOnly = true,
                        path = "/t/$slug",
                    )
                    call.respondRedirect("/t/$slug/mfa-challenge")
                    return@post
                }

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
                        call.clearAuthContextCookie(slug)
                        val code = codeResult.value.code
                        val redirect =
                            buildString {
                                append(redirectUri)
                                append("?code=").append(code)
                                if (!oauthParams.state.isNullOrBlank()) append("&state=").append(oauthParams.state)
                            }
                        call.respondRedirect(redirect)
                    }
                    is OAuthResult.Failure -> {
                        call.respondHtml(
                            HttpStatusCode.BadRequest,
                            AuthView.loginPage(
                                tenantSlug = slug,
                                theme = theme,
                                workspaceName = workspaceName,
                                error = codeResult.error.toDescription(),
                                oauthParams = oauthParams,
                                enabledProviders = enabledProviders,
                                registrationEnabled = tenant?.registrationEnabled ?: true,
                            ),
                        )
                    }
                }
            }
        }
    }

    post("/protocol/openid-connect/token") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val ipAddress = call.request.local.remoteAddress

        if (!tokenRateLimiter.isAllowed("token:$ipAddress:$slug")) {
            return@post call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf(
                    "error" to "rate_limit_exceeded",
                    "error_description" to "Too many token requests. Please slow down.",
                ),
            )
        }

        val params = call.receiveParameters()
        val grantType = params["grant_type"] ?: ""
        val userAgent = call.request.headers["User-Agent"]
        val (formClientId, formClientSecret) = extractClientCredentials(call, params)

        when (grantType) {
            "authorization_code" -> {
                val code =
                    params["code"] ?: return@post oauthError(call, "invalid_request", "code is required")
                val redirectUri =
                    params["redirect_uri"]
                        ?: return@post oauthError(call, "invalid_request", "redirect_uri is required")
                val codeVerifier = params["code_verifier"]

                when (
                    val result =
                        oauthService.exchangeAuthorizationCode(
                            tenantSlug = slug,
                            code = code,
                            clientId =
                                formClientId ?: return@post oauthError(
                                    call,
                                    "invalid_client",
                                    "client_id required",
                                ),
                            redirectUri = redirectUri,
                            codeVerifier = codeVerifier,
                            clientSecret = formClientSecret,
                            ipAddress = ipAddress,
                            userAgent = userAgent,
                        )
                ) {
                    is OAuthResult.Success -> call.respond(result.value)
                    is OAuthResult.Failure ->
                        oauthError(
                            call,
                            result.error.toErrorCode(),
                            result.error.toDescription(),
                        )
                }
            }

            "client_credentials" -> {
                val scopes = params["scope"] ?: ""

                when (
                    val result =
                        oauthService.clientCredentials(
                            tenantSlug = slug,
                            clientId =
                                formClientId ?: return@post oauthError(
                                    call,
                                    "invalid_client",
                                    "client_id required",
                                ),
                            clientSecret =
                                formClientSecret ?: return@post oauthError(
                                    call,
                                    "invalid_client",
                                    "client_secret required",
                                ),
                            scopes = scopes,
                            ipAddress = ipAddress,
                        )
                ) {
                    is OAuthResult.Success -> call.respond(result.value)
                    is OAuthResult.Failure ->
                        oauthError(
                            call,
                            result.error.toErrorCode(),
                            result.error.toDescription(),
                        )
                }
            }

            "refresh_token" -> {
                val refreshToken =
                    params["refresh_token"]
                        ?: return@post oauthError(call, "invalid_request", "refresh_token is required")

                when (
                    val result =
                        oauthService.refreshTokens(
                            tenantSlug = slug,
                            refreshToken = refreshToken,
                            clientId =
                                formClientId ?: return@post oauthError(
                                    call,
                                    "invalid_client",
                                    "client_id required",
                                ),
                            ipAddress = ipAddress,
                            userAgent = userAgent,
                        )
                ) {
                    is OAuthResult.Success -> call.respond(result.value)
                    is OAuthResult.Failure ->
                        oauthError(
                            call,
                            result.error.toErrorCode(),
                            result.error.toDescription(),
                        )
                }
            }

            else -> oauthError(call, "unsupported_grant_type", "Unsupported grant_type: $grantType")
        }
    }

    get("/protocol/openid-connect/userinfo") {
        val bearerToken =
            extractBearerToken(call)
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    buildJsonObject { put("error", "invalid_token") },
                )

        val userInfo =
            oauthService.getUserInfo(bearerToken)
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    buildJsonObject { put("error", "invalid_token") },
                )

        call.respond(
            buildJsonObject {
                put("sub", userInfo.sub)
                put("preferred_username", userInfo.username)
                put("email", userInfo.email)
                put("email_verified", userInfo.emailVerified)
                put("name", userInfo.name)
            },
        )
    }

    post("/protocol/openid-connect/revoke") {
        val params = call.receiveParameters()
        val token =
            params["token"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "token parameter is required")
                },
            )

        oauthService.revokeToken(token)
        call.respond(HttpStatusCode.OK)
    }

    post("/protocol/openid-connect/introspect") {
        val params = call.receiveParameters()
        val token = params["token"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val typeHint = params["token_type_hint"]
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        when (val result = oauthService.introspectToken(slug, token, typeHint)) {
            is IntrospectionResult.Inactive ->
                call.respond(buildJsonObject { put("active", false) })
            is IntrospectionResult.Active ->
                call.respond(
                    buildJsonObject {
                        put("active", true)
                        put("sub", result.sub)
                        put("username", result.username ?: "")
                        put("email", result.email ?: "")
                        put("scope", result.scopes.joinToString(" "))
                        put("exp", result.expiresAt)
                        put("client_id", result.clientId ?: "")
                    },
                )
        }
    }

    route("/protocol/openid-connect/logout") {
        get {
            val bearerToken =
                extractBearerToken(call)
                    ?: call.request.queryParameters["id_token_hint"]

            if (bearerToken != null) {
                val revokeAll = call.request.queryParameters["global_logout"] == "true"
                oauthService.endSession(bearerToken, revokeAll, call.request.local.remoteAddress)
            }

            val postLogoutUri = call.request.queryParameters["post_logout_redirect_uri"]
            if (!postLogoutUri.isNullOrBlank()) {
                // Only allow post-logout redirect to same origin to prevent open redirect
                val local = call.request.local
                val origin = "${local.scheme}://${local.serverHost}:${local.serverPort}"
                if (postLogoutUri.startsWith(origin) || postLogoutUri.startsWith("/")) {
                    call.respondRedirect(postLogoutUri)
                } else {
                    val slug = call.parameters["slug"] ?: "master"
                    call.respondRedirect("/t/$slug/login")
                }
            } else {
                val slug = call.parameters["slug"] ?: "master"
                call.respondRedirect("/t/$slug/login")
            }
        }

        post {
            val params = call.receiveParameters()
            val token = params["token"] ?: extractBearerToken(call)

            if (token != null) {
                val revokeAll = params["global_logout"] == "true"
                oauthService.endSession(token, revokeAll, call.request.local.remoteAddress)
            }

            call.respond(HttpStatusCode.OK)
        }
    }
}
