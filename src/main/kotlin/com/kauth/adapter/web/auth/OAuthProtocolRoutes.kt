package com.kauth.adapter.web.auth

import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.IntrospectionResult
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
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
                put("authorization_endpoint", "$issuer/protocol/openid-connect/auth")
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

    get("/protocol/openid-connect/auth") {
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
            ),
        )
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
                call.respondRedirect(postLogoutUri)
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
