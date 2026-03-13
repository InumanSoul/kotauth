package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.IntrospectionResult
import com.kauth.domain.service.OAuthError
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.domain.model.User
import com.kauth.infrastructure.RateLimiter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Web adapter — HTTP routes for the auth module (Phase 2).
 *
 * Tenant-scoped endpoints under /t/{slug}/:
 *
 *   Browser flows (Phase 1, updated):
 *     GET/POST /login
 *     GET/POST /register
 *
 *   OIDC discovery + keys:
 *     GET  /.well-known/openid-configuration   — discovery document
 *     GET  /protocol/openid-connect/certs      — JWKS
 *
 *   OAuth2 protocol:
 *     GET  /protocol/openid-connect/auth       — authorization endpoint
 *     POST /protocol/openid-connect/token      — token endpoint (all grant types)
 *     GET|POST /protocol/openid-connect/logout — end session
 *     GET  /protocol/openid-connect/userinfo   — userinfo (bearer auth)
 *     POST /protocol/openid-connect/revoke     — token revocation
 *     POST /protocol/openid-connect/introspect — token introspection
 *
 * Responsibility: parse HTTP → call domain service → translate result to HTTP.
 * No business logic here. No SQL here.
 */
fun Route.authRoutes(
    authService: AuthService,
    oauthService: OAuthService,
    tenantRepository: TenantRepository,
    loginRateLimiter: RateLimiter,
    registerRateLimiter: RateLimiter
) {

    route("/t/{slug}") {

        // ------------------------------------------------------------------
        // Login — browser UI (updated for OAuth2 passthrough)
        // ------------------------------------------------------------------

        get("/login") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            val registered = call.request.queryParameters["registered"] == "true"

            // Preserve OAuth2 params from authorization endpoint redirect
            val oauthParams = call.request.queryParameters.toOAuthParams()

            call.respondHtml(HttpStatusCode.OK, AuthView.loginPage(slug, theme, success = registered, oauthParams = oauthParams))
        }

        post("/login") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val password = params["password"] ?: ""
            val ipAddress = call.request.local.remoteAddress
            val oauthParams = params.toOAuthParams()

            // Rate limiting — per-IP, per-endpoint
            val rateLimitKey = "login:$ipAddress"
            if (!loginRateLimiter.isAllowed(rateLimitKey)) {
                return@post call.respondHtml(
                    HttpStatusCode.TooManyRequests,
                    AuthView.loginPage(slug, theme, error = "Too many login attempts. Please wait a moment and try again.", oauthParams = oauthParams)
                )
            }

            // Use authenticate() for both flows — it returns the User without issuing tokens
            val userAgent = call.request.headers["User-Agent"]
            when (val result = authService.authenticate(slug, username, password, ipAddress, userAgent)) {
                is AuthResult.Failure -> call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.loginPage(slug, theme, error = result.error.toMessage(), oauthParams = oauthParams)
                )
                is AuthResult.Success -> {
                    val user = result.value

                    if (oauthParams.isOAuthFlow) {
                        // Authorization Code Flow: issue code and redirect
                        val clientId    = oauthParams.clientId ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing client_id")
                        val redirectUri = oauthParams.redirectUri ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing redirect_uri")

                        when (val codeResult = oauthService.issueAuthorizationCode(
                            tenantSlug          = slug,
                            userId              = user.id!!,
                            clientId            = clientId,
                            redirectUri         = redirectUri,
                            scopes              = oauthParams.scope ?: "openid",
                            codeChallenge       = oauthParams.codeChallenge,
                            codeChallengeMethod = oauthParams.codeChallengeMethod,
                            nonce               = oauthParams.nonce,
                            state               = oauthParams.state,
                            ipAddress           = ipAddress
                        )) {
                            is OAuthResult.Success -> {
                                val code  = codeResult.value.code
                                val state = oauthParams.state
                                val redirect = buildString {
                                    append(redirectUri)
                                    append("?code=").append(code)
                                    if (!state.isNullOrBlank()) append("&state=").append(state)
                                }
                                call.respondRedirect(redirect)
                            }
                            is OAuthResult.Failure -> {
                                call.respondHtml(
                                    HttpStatusCode.BadRequest,
                                    AuthView.loginPage(slug, theme, error = codeResult.error.toDescription(), oauthParams = oauthParams)
                                )
                            }
                        }
                    } else {
                        // Direct login — issue tokens and persist a server-side session
                        when (val tokenResult = authService.login(slug, username, password, ipAddress, userAgent)) {
                            is AuthResult.Success -> call.respond(tokenResult.value)
                            is AuthResult.Failure -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_credentials"))
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Registration — browser UI
        // ------------------------------------------------------------------

        get("/register") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            call.respondHtml(HttpStatusCode.OK, AuthView.registerPage(slug, theme))
        }

        post("/register") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val theme = tenantRepository.findBySlug(slug)?.theme ?: TenantTheme.DEFAULT
            val ipAddress = call.request.local.remoteAddress

            // Rate limiting on registration
            val rateLimitKey = "register:$ipAddress"
            if (!registerRateLimiter.isAllowed(rateLimitKey)) {
                return@post call.respondHtml(
                    HttpStatusCode.TooManyRequests,
                    AuthView.registerPage(slug, theme, error = "Too many registration attempts. Please wait a moment.")
                )
            }

            val params = call.receiveParameters()
            val username        = params["username"]?.trim() ?: ""
            val email           = params["email"]?.trim() ?: ""
            val fullName        = params["fullName"]?.trim() ?: ""
            val password        = params["password"] ?: ""
            val confirmPassword = params["confirmPassword"] ?: ""
            val prefill         = RegisterPrefill(username = username, email = email, fullName = fullName)

            when (val result = authService.register(slug, username, email, fullName, password, confirmPassword)) {
                is AuthResult.Success ->
                    call.respondRedirect("/t/$slug/login?registered=true")
                is AuthResult.Failure ->
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AuthView.registerPage(slug, theme, error = result.error.toMessage(), prefill = prefill)
                    )
            }
        }

        // ==================================================================
        // OIDC Discovery
        // ==================================================================

        get("/.well-known/openid-configuration") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "tenant_not_found"))

            val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
            val issuer = tenant.issuerUrl ?: "$baseUrl/t/$slug"

            call.respond(mapOf(
                "issuer"                                 to issuer,
                "authorization_endpoint"                 to "$issuer/protocol/openid-connect/auth",
                "token_endpoint"                         to "$issuer/protocol/openid-connect/token",
                "userinfo_endpoint"                      to "$issuer/protocol/openid-connect/userinfo",
                "jwks_uri"                               to "$issuer/protocol/openid-connect/certs",
                "end_session_endpoint"                   to "$issuer/protocol/openid-connect/logout",
                "revocation_endpoint"                    to "$issuer/protocol/openid-connect/revoke",
                "introspection_endpoint"                 to "$issuer/protocol/openid-connect/introspect",
                "response_types_supported"               to listOf("code"),
                "grant_types_supported"                  to listOf("authorization_code", "client_credentials", "refresh_token"),
                "subject_types_supported"                to listOf("public"),
                "id_token_signing_alg_values_supported"  to listOf("RS256"),
                "token_endpoint_auth_methods_supported"  to listOf("client_secret_post", "client_secret_basic"),
                "scopes_supported"                       to listOf("openid", "profile", "email"),
                "claims_supported"                       to listOf("sub", "iss", "aud", "exp", "iat", "email", "email_verified", "name", "preferred_username"),
                "code_challenge_methods_supported"       to listOf("S256")
            ))
        }

        // ==================================================================
        // JWKS — public keys for offline token verification
        // ==================================================================

        get("/protocol/openid-connect/certs") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val jwks = oauthService.getJwks(tenant.id)
            call.respond(mapOf("keys" to jwks))
        }

        // ==================================================================
        // Authorization endpoint — start of the Authorization Code Flow
        // ==================================================================

        get("/protocol/openid-connect/auth") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val q = call.request.queryParameters

            val responseType = q["response_type"] ?: ""
            val clientId     = q["client_id"] ?: ""
            val redirectUri  = q["redirect_uri"] ?: ""
            val scope        = q["scope"] ?: "openid"
            val state        = q["state"]
            val nonce        = q["nonce"]
            val codeChallenge       = q["code_challenge"]
            val codeChallengeMethod = q["code_challenge_method"]

            if (responseType != "code") {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "unsupported_response_type",
                    "error_description" to "Only 'code' response_type is supported"
                ))
                return@get
            }

            if (clientId.isBlank() || redirectUri.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "invalid_request",
                    "error_description" to "client_id and redirect_uri are required"
                ))
                return@get
            }

            // Validate redirect URI before showing the login page (security — don't redirect to unknown URIs)
            val tenant = tenantRepository.findBySlug(slug)
            if (tenant == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "tenant_not_found"))
                return@get
            }

            // Redirect to login page preserving all OAuth2 params
            val oauthParams = AuthView.OAuthParams(
                responseType        = responseType,
                clientId            = clientId,
                redirectUri         = redirectUri,
                scope               = scope,
                state               = state,
                codeChallenge       = codeChallenge,
                codeChallengeMethod = codeChallengeMethod,
                nonce               = nonce
            )

            call.respondHtml(HttpStatusCode.OK, AuthView.loginPage(slug, tenant.theme, oauthParams = oauthParams))
        }

        // ==================================================================
        // Token endpoint — handles all grant types
        // ==================================================================

        post("/protocol/openid-connect/token") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val params = call.receiveParameters()
            val grantType = params["grant_type"] ?: ""
            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]

            // Support both form body and Basic auth for client_id/secret
            val (formClientId, formClientSecret) = extractClientCredentials(call, params)

            when (grantType) {

                "authorization_code" -> {
                    val code         = params["code"] ?: return@post oauthError(call, "invalid_request", "code is required")
                    val redirectUri  = params["redirect_uri"] ?: return@post oauthError(call, "invalid_request", "redirect_uri is required")
                    val codeVerifier = params["code_verifier"]

                    when (val result = oauthService.exchangeAuthorizationCode(
                        tenantSlug    = slug,
                        code          = code,
                        clientId      = formClientId ?: return@post oauthError(call, "invalid_client", "client_id required"),
                        redirectUri   = redirectUri,
                        codeVerifier  = codeVerifier,
                        clientSecret  = formClientSecret,
                        ipAddress     = ipAddress,
                        userAgent     = userAgent
                    )) {
                        is OAuthResult.Success -> call.respond(result.value)
                        is OAuthResult.Failure -> oauthError(call, result.error.toErrorCode(), result.error.toDescription())
                    }
                }

                "client_credentials" -> {
                    val scopes = params["scope"] ?: ""

                    when (val result = oauthService.clientCredentials(
                        tenantSlug    = slug,
                        clientId      = formClientId ?: return@post oauthError(call, "invalid_client", "client_id required"),
                        clientSecret  = formClientSecret ?: return@post oauthError(call, "invalid_client", "client_secret required"),
                        scopes        = scopes,
                        ipAddress     = ipAddress
                    )) {
                        is OAuthResult.Success -> call.respond(result.value)
                        is OAuthResult.Failure -> oauthError(call, result.error.toErrorCode(), result.error.toDescription())
                    }
                }

                "refresh_token" -> {
                    val refreshToken = params["refresh_token"] ?: return@post oauthError(call, "invalid_request", "refresh_token is required")

                    when (val result = oauthService.refreshTokens(
                        tenantSlug    = slug,
                        refreshToken  = refreshToken,
                        clientId      = formClientId ?: return@post oauthError(call, "invalid_client", "client_id required"),
                        ipAddress     = ipAddress,
                        userAgent     = userAgent
                    )) {
                        is OAuthResult.Success -> call.respond(result.value)
                        is OAuthResult.Failure -> oauthError(call, result.error.toErrorCode(), result.error.toDescription())
                    }
                }

                else -> oauthError(call, "unsupported_grant_type", "Unsupported grant_type: $grantType")
            }
        }

        // ==================================================================
        // Userinfo endpoint — OIDC Core §5.3
        // ==================================================================

        get("/protocol/openid-connect/userinfo") {
            val bearerToken = extractBearerToken(call)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))

            val userInfo = oauthService.getUserInfo(bearerToken)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))

            call.respond(mapOf(
                "sub"             to userInfo.sub,
                "preferred_username" to userInfo.username,
                "email"           to userInfo.email,
                "email_verified"  to userInfo.emailVerified,
                "name"            to userInfo.name
            ))
        }

        // ==================================================================
        // Token revocation — RFC 7009
        // ==================================================================

        post("/protocol/openid-connect/revoke") {
            val params = call.receiveParameters()
            val token = params["token"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "invalid_request",
                "error_description" to "token parameter is required"
            ))

            oauthService.revokeToken(token)
            // RFC 7009: always return 200 OK (don't leak token validity)
            call.respond(HttpStatusCode.OK)
        }

        // ==================================================================
        // Token introspection — RFC 7662
        // ==================================================================

        post("/protocol/openid-connect/introspect") {
            val params = call.receiveParameters()
            val token     = params["token"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val typeHint  = params["token_type_hint"]
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)

            when (val result = oauthService.introspectToken(slug, token, typeHint)) {
                is IntrospectionResult.Inactive -> call.respond(mapOf("active" to false))
                is IntrospectionResult.Active   -> call.respond(mapOf(
                    "active"     to true,
                    "sub"        to result.sub,
                    "username"   to (result.username ?: ""),
                    "email"      to (result.email ?: ""),
                    "scope"      to result.scopes.joinToString(" "),
                    "exp"        to result.expiresAt,
                    "client_id"  to (result.clientId ?: "")
                ))
            }
        }

        // ==================================================================
        // End session / logout — OIDC Session Management
        // ==================================================================

        route("/protocol/openid-connect/logout") {
            get {
                val bearerToken = extractBearerToken(call)
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
}

// ============================================================================
// HTTP helpers
// ============================================================================

private suspend fun oauthError(
    call: ApplicationCall,
    error: String,
    description: String,
    status: HttpStatusCode = HttpStatusCode.BadRequest
) {
    call.respond(status, mapOf(
        "error" to error,
        "error_description" to description
    ))
}

private fun extractBearerToken(call: ApplicationCall): String? {
    val auth = call.request.headers["Authorization"] ?: return null
    if (!auth.startsWith("Bearer ", ignoreCase = true)) return null
    return auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
}

/**
 * Extracts client_id and client_secret from either:
 *   - HTTP Basic auth (Authorization: Basic base64(client_id:client_secret))
 *   - Form body (client_id + client_secret parameters)
 */
private fun extractClientCredentials(
    call: ApplicationCall,
    params: Parameters
): Pair<String?, String?> {
    val auth = call.request.headers["Authorization"]
    if (auth != null && auth.startsWith("Basic ", ignoreCase = true)) {
        val decoded = java.util.Base64.getDecoder()
            .decode(auth.removePrefix("Basic ").trim())
            .toString(Charsets.UTF_8)
        val sep = decoded.indexOf(':')
        if (sep > 0) {
            return decoded.substring(0, sep) to decoded.substring(sep + 1)
        }
    }
    return params["client_id"] to params["client_secret"]
}

private fun Parameters.toOAuthParams() = AuthView.OAuthParams(
    responseType        = this["response_type"],
    clientId            = this["oauth_client_id"] ?: this["client_id"],
    redirectUri         = this["redirect_uri"],
    scope               = this["scope"],
    state               = this["state"],
    codeChallenge       = this["code_challenge"],
    codeChallengeMethod = this["code_challenge_method"],
    nonce               = this["nonce"]
)

private fun OAuthError.toErrorCode(): String = when (this) {
    is OAuthError.TenantNotFound       -> "invalid_request"
    is OAuthError.InvalidClient        -> "invalid_client"
    is OAuthError.InvalidGrant         -> "invalid_grant"
    is OAuthError.InvalidRequest       -> "invalid_request"
    is OAuthError.InvalidRedirectUri   -> "invalid_request"
    is OAuthError.PkceRequired         -> "invalid_request"
    is OAuthError.UnsupportedGrantType -> "unsupported_grant_type"
}

private fun OAuthError.toDescription(): String = when (this) {
    is OAuthError.TenantNotFound       -> "Tenant not found"
    is OAuthError.InvalidClient        -> this.reason
    is OAuthError.InvalidGrant         -> this.reason
    is OAuthError.InvalidRequest       -> this.reason
    is OAuthError.InvalidRedirectUri   -> "Invalid redirect_uri: ${this.uri}"
    is OAuthError.PkceRequired         -> "PKCE is required for public clients"
    is OAuthError.UnsupportedGrantType -> "Unsupported grant type"
}

private fun AuthError.toMessage(): String = when (this) {
    is AuthError.InvalidCredentials   -> "Invalid username or password."
    is AuthError.TenantNotFound       -> "Tenant not found."
    is AuthError.RegistrationDisabled -> "Registration is not enabled for this tenant."
    is AuthError.UserAlreadyExists    -> "That username is already taken."
    is AuthError.EmailAlreadyExists   -> "An account with that email already exists."
    is AuthError.WeakPassword         -> "Password must be at least $minLength characters."
    is AuthError.ValidationError      -> this.message
}
