package com.kauth.adapter.web.auth

import com.kauth.adapter.web.admin.resolvedBaseUrl
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.IntrospectionResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.PortalClientProvisioning
import io.ktor.http.HttpStatusCode
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
import java.time.Instant

/**
 * OIDC Core §3.1.2.1 `prompt` values we recognize at GET /authorize.
 *
 * - `none`: silent auth — never display UI. Phase 3 honors this against the
 *   SSO witness cookie; Phase 2 always returns `login_required`.
 * - `login`: force re-auth — always render the login form.
 * - `consent`: force consent screen. We have no separate consent UI yet, so
 *   it is treated like `login` for now (server simply re-authenticates).
 * - `select_account`: account picker. Single-account-per-browser today, so
 *   identical to `login` in behavior.
 */
private val OIDC_SUPPORTED_PROMPTS = setOf("none", "login", "consent", "select_account")

/**
 * Best-effort `sub` claim extraction from an OIDC ID token used as
 * `id_token_hint`. The token was issued by us in a prior login and the RP is
 * now hinting at the desired session — we read the sub but do NOT verify the
 * signature here. Mismatch only makes us fall back to interactive auth, so the
 * worst case from an attacker-supplied hint is "no silent auth", which is the
 * safe default. Strict signature verification would harden the flow further;
 * tracked as a follow-up to v1.8.1.
 */
private fun decodeIdTokenSub(token: String): String? =
    try {
        val parts = token.split(".")
        if (parts.size != 3) {
            null
        } else {
            val payload =
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(parts[1]),
                    Charsets.UTF_8,
                )
            val element = Json.parseToJsonElement(payload)
            element.jsonObject["sub"]?.jsonPrimitive?.contentOrNull
        }
    } catch (_: Exception) {
        null
    }

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
    ssoTtlSeconds: Long,
) {
    get("/.well-known/openid-configuration") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant =
            ctx.tenant
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "tenant_not_found"))

        val openidBaseUrl = call.resolvedBaseUrl()
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
                        add("auth_time")
                    },
                )
                put("code_challenge_methods_supported", buildJsonArray { add("S256") })
                put("resource_indicators_supported", true)
                put(
                    "prompt_values_supported",
                    buildJsonArray {
                        OIDC_SUPPORTED_PROMPTS.forEach { add(it) }
                    },
                )
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

        val promptValues =
            q["prompt"]
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        val unknownPrompts = promptValues.filterNot { it in OIDC_SUPPORTED_PROMPTS }
        if (unknownPrompts.isNotEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "invalid_request",
                    "error_description" to "Unsupported prompt value(s): ${unknownPrompts.joinToString(",")}",
                ),
            )
            return@get
        }
        // OIDC Core §3.1.2.1: `none` MUST NOT appear with any other prompt value.
        if ("none" in promptValues && promptValues.size > 1) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "invalid_request",
                    "error_description" to "prompt=none cannot be combined with other prompt values",
                ),
            )
            return@get
        }

        // If no OAuth query params, check for an existing auth context cookie.
        // This handles returning from registration/password-reset during an active OAuth flow.
        // If no cookie either, redirect to the portal login which initiates a proper OAuth flow.
        val hasOAuthParams = responseType.isNotBlank() || clientId.isNotBlank() || redirectUri.isNotBlank()

        if (!hasOAuthParams) {
            val existingContext = call.getAuthContext(encryptionService)
            if (existingContext != null) {
                // OAuth flow in progress via cookie — render login within that context
                val tenant = ctx.tenant
                val registered = q["registered"] == "true"
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
                        ctx = ctx.viewContext,
                        success = registered,
                        oauthParams = existingContext,
                        enabledProviders = enabledProviders,
                        registrationEnabled = tenant?.registrationEnabled ?: true,
                        magicLinkEnabled = tenant?.securityConfig?.magicLinkEnabled == true,
                        passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                        emailOtpLoginEnabled = tenant?.securityConfig?.emailOtpLoginEnabled == true,
                    ),
                )
                return@get
            }
            // No OAuth flow — redirect to portal login which starts a proper flow
            call.respondRedirect("/t/$slug/account/login")
            return@get
        }
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

        val isSilentOnly = "none" in promptValues
        val forceReauth = promptValues.any { it == "login" || it == "consent" || it == "select_account" }

        // Open-redirect guard for any flow that may issue a redirect-uri-based response
        // (silent auth success, prompt=none failure). The validation must happen before
        // we ever build a redirect string from `redirectUri` query data.
        val redirectUriTrusted = oauthService.validateRedirectUri(slug, clientId, redirectUri)
        if (isSilentOnly && !redirectUriTrusted) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "invalid_request",
                    "error_description" to "Invalid redirect_uri for client",
                ),
            )
            return@get
        }

        val requestedResources =
            call.parameters
                .getAll("resource")
                .orEmpty()
                .filter { it.isNotBlank() }
                .map { it.trim() }
        val audienceResolution =
            if (requestedResources.isNotEmpty()) {
                oauthService.resolveAudiencesForClient(slug, clientId, requestedResources)
            } else {
                OAuthService.AudienceResolution.Ok(emptyList())
            }
        if (audienceResolution is OAuthService.AudienceResolution.Failed) {
            call.respondRedirect(
                buildString {
                    append(redirectUri)
                    append("?error=invalid_target")
                    append("&error_description=").append(encodeParam(audienceResolution.error.reason))
                    if (!state.isNullOrBlank()) append("&state=").append(encodeParam(state))
                },
            )
            return@get
        }
        val resolvedResources = (audienceResolution as OAuthService.AudienceResolution.Ok).values

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
                resources = resolvedResources,
            )

        // ---- Silent auth attempt -------------------------------------------
        // Skip when the RP explicitly asked for a fresh login. Otherwise read
        // the SSO witness cookie and, if it still matches all the RP's hints
        // (max_age, id_token_hint, current MFA policy), issue an authorization
        // code and bounce back to the redirect_uri without showing UI.
        if (!forceReauth && redirectUriTrusted) {
            val sso = call.getSsoCookie(encryptionService)
            if (sso != null && sso.tenantId == tenant.id.value) {
                val maxAge = q["max_age"]?.toLongOrNull()
                // max_age=0 is the OIDC sentinel for "force a fresh credential proof now",
                // so any prior cookie — however recent — fails the check.
                val withinMaxAge =
                    when {
                        maxAge == null -> true
                        maxAge <= 0L -> false
                        else -> (Instant.now().epochSecond - sso.authTime.epochSecond) <= maxAge
                    }
                val hintSub = q["id_token_hint"]?.let { decodeIdTokenSub(it) }
                val hintMatches = hintSub == null || hintSub == sso.userId.toString()
                // If the tenant currently requires MFA but the cookie was minted
                // before that requirement was satisfied, silent auth is not safe
                // — fall back to interactive so the policy can re-trigger.
                val mfaPolicy = tenant.mfaPolicy
                val mfaSatisfied = sso.mfaCompleted || mfaPolicy == "optional"

                if (withinMaxAge && hintMatches && mfaSatisfied) {
                    val codeResult =
                        oauthService.issueAuthorizationCode(
                            tenantSlug = slug,
                            userId =
                                com.kauth.domain.model
                                    .UserId(sso.userId),
                            clientId = clientId,
                            redirectUri = redirectUri,
                            scopes = scope,
                            codeChallenge = codeChallenge,
                            codeChallengeMethod = codeChallengeMethod,
                            nonce = nonce,
                            state = state,
                            ipAddress = call.request.local.remoteAddress,
                            authTime = sso.authTime,
                            resources = resolvedResources,
                        )
                    if (codeResult is OAuthResult.Success) {
                        val redirect =
                            buildString {
                                append(redirectUri)
                                append("?code=").append(codeResult.value.code)
                                if (!state.isNullOrBlank()) append("&state=").append(encodeParam(state))
                            }
                        call.respondRedirect(redirect)
                        return@get
                    }
                    // OAuth failure (e.g., PKCE required + missing challenge): fall through
                    // to the interactive flow; never leak a silent-auth failure to the client.
                }
            }
        }

        // Silent auth did not happen. If the caller said `prompt=none`, surface
        // login_required per OIDC §3.1.2.6.
        if (isSilentOnly) {
            val errorRedirect =
                buildString {
                    append(redirectUri)
                    append("?error=login_required")
                    append("&error_description=").append(encodeParam("Silent authentication failed"))
                    if (!state.isNullOrBlank()) append("&state=").append(encodeParam(state))
                }
            call.respondRedirect(errorRedirect)
            return@get
        }

        if (forceReauth) {
            call.clearSsoCookie(slug)
        }

        call.setAuthContextCookie(
            oauthParams,
            slug,
            encryptionService,
            baseUrl.startsWith("https://", ignoreCase = true),
        )

        val enabledProviders =
            identityProviderRepository
                ?.findEnabledByTenant(tenant.id)
                ?.map { it.provider } ?: emptyList()

        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.loginPage(
                tenantSlug = slug,
                ctx = ctx.viewContext,
                oauthParams = oauthParams,
                enabledProviders = enabledProviders,
                registrationEnabled = tenant.registrationEnabled,
                magicLinkEnabled = tenant.securityConfig.magicLinkEnabled,
                passwordLoginEnabled = tenant.securityConfig.passwordLoginEnabled,
                emailOtpLoginEnabled = tenant.securityConfig.emailOtpLoginEnabled,
            ),
        )
    }

    post("/authorize") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
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
                    ctx.viewContext,
                    error = "Too many login attempts. Please wait a moment and try again.",
                    enabledProviders = enabledProviders,
                    registrationEnabled = tenant?.registrationEnabled ?: true,
                    magicLinkEnabled = tenant?.securityConfig?.magicLinkEnabled == true,
                    passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                    emailOtpLoginEnabled = tenant?.securityConfig?.emailOtpLoginEnabled == true,
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
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.loginPage(
                        tenantSlug = slug,
                        ctx = ctx.viewContext,
                        error = result.error.toMessage(),
                        oauthParams = oauthParams,
                        enabledProviders = enabledProviders,
                        registrationEnabled = tenant?.registrationEnabled ?: true,
                        magicLinkEnabled = tenant?.securityConfig?.magicLinkEnabled == true,
                        passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                        emailOtpLoginEnabled = tenant?.securityConfig?.emailOtpLoginEnabled == true,
                    ),
                )
            }
            is AuthResult.Success -> {
                val user = result.value
                if (tenant == null) {
                    return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "tenant_not_found"))
                }

                // Enforce MFA enrollment policy before issuing a challenge (skip for portal logins)
                val isPortalLogin = oauthParams.clientId == PortalClientProvisioning.PORTAL_CLIENT_ID
                if (mfaService != null && !isPortalLogin) {
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
                                    ctx = ctx.viewContext,
                                    error =
                                        "Multi-factor authentication is required for your account. " +
                                            "Please sign in to the user portal and enable MFA under Security settings.",
                                    oauthParams = oauthParams,
                                    enabledProviders = enabledProviders,
                                    registrationEnabled = tenant.registrationEnabled,
                                    magicLinkEnabled = tenant.securityConfig.magicLinkEnabled,
                                    passwordLoginEnabled = tenant.securityConfig.passwordLoginEnabled,
                                    emailOtpLoginEnabled = tenant.securityConfig.emailOtpLoginEnabled,
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
                        secure = baseUrl.startsWith("https://", ignoreCase = true),
                        path = "/t/$slug",
                    )
                    call.respondRedirect("/t/$slug/mfa-challenge")
                    return@post
                }

                call.completeAuthorizationCodeFlow(
                    slug = slug,
                    userId = user.id!!,
                    tenantId = tenant.id,
                    oauthParams = oauthParams,
                    ipAddress = ipAddress,
                    authTime = java.time.Instant.now(),
                    mfaCompleted = false,
                    ssoTtlSeconds = ssoTtlSeconds,
                    secure = baseUrl.startsWith("https://", ignoreCase = true),
                    oauthService = oauthService,
                    encryptionService = encryptionService,
                    renderError = { message ->
                        call.respondHtml(
                            HttpStatusCode.BadRequest,
                            AuthView.loginPage(
                                tenantSlug = slug,
                                ctx = ctx.viewContext,
                                error = message,
                                oauthParams = oauthParams,
                                enabledProviders = enabledProviders,
                                registrationEnabled = tenant.registrationEnabled,
                                magicLinkEnabled = tenant.securityConfig.magicLinkEnabled,
                                passwordLoginEnabled = tenant.securityConfig.passwordLoginEnabled,
                                emailOtpLoginEnabled = tenant.securityConfig.emailOtpLoginEnabled,
                            ),
                        )
                    },
                )
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
                val requestedResources = params.getAll("resource").orEmpty().filter { it.isNotBlank() }

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
                            requestedResources = requestedResources,
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
                val resources = params.getAll("resource").orEmpty().filter { it.isNotBlank() }

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
                            resources = resources,
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
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val params = call.receiveParameters()
        val token =
            params["token"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "token parameter is required")
                },
            )
        val (clientId, clientSecret) = extractClientCredentials(call, params)

        // RFC 7009 §2.1: the caller must authenticate before any revocation
        if (!oauthService.revokeToken(slug, token, clientId, clientSecret)) {
            return@post call.respond(
                HttpStatusCode.Unauthorized,
                buildJsonObject {
                    put("error", "invalid_client")
                    put("error_description", "Client authentication failed")
                },
            )
        }
        call.respond(HttpStatusCode.OK)
    }

    post("/protocol/openid-connect/introspect") {
        val params = call.receiveParameters()
        val token = params["token"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val typeHint = params["token_type_hint"]
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val (clientId, clientSecret) = extractClientCredentials(call, params)

        when (val result = oauthService.introspectToken(slug, token, clientId, clientSecret, typeHint)) {
            is IntrospectionResult.Unauthorized ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    buildJsonObject {
                        put("error", "invalid_client")
                        put("error_description", "Client authentication failed")
                    },
                )
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
                        when (result.aud.size) {
                            0 -> Unit
                            1 -> put("aud", result.aud.first())
                            else -> put("aud", buildJsonArray { result.aud.forEach { add(it) } })
                        }
                    },
                )
        }
    }

    route("/protocol/openid-connect/logout") {
        get {
            val slug = call.parameters["slug"] ?: "master"
            val bearerToken =
                extractBearerToken(call)
                    ?: call.request.queryParameters["id_token_hint"]

            if (bearerToken != null) {
                val revokeAll = call.request.queryParameters["global_logout"] == "true"
                oauthService.endSession(bearerToken, revokeAll, call.request.local.remoteAddress)
            }

            // OIDC end_session must also kill the SSO witness — otherwise the
            // very next /authorize would silent-auth the user back in via the
            // KOTAUTH_SSO cookie they just nominally signed out of.
            call.clearSsoCookie(slug)

            val postLogoutUri = call.request.queryParameters["post_logout_redirect_uri"]
            if (postLogoutUri.isNullOrBlank() || !isSafePostLogoutRedirect(postLogoutUri, call.resolvedBaseUrl())) {
                call.respondRedirect("/t/$slug/authorize")
            } else {
                call.respondRedirect(postLogoutUri)
            }
        }

        post {
            val slug = call.parameters["slug"] ?: "master"
            val params = call.receiveParameters()
            val token = params["token"] ?: extractBearerToken(call)

            if (token != null) {
                val revokeAll = params["global_logout"] == "true"
                oauthService.endSession(token, revokeAll, call.request.local.remoteAddress)
            }

            call.clearSsoCookie(slug)
            call.respond(HttpStatusCode.OK)
        }
    }
}

private fun isSafePostLogoutRedirect(
    uri: String,
    origin: String,
): Boolean {
    val trimmed = uri.trim()
    // "//evil.com" / "/\evil.com" / "\\evil.com" start with "/" but resolve to external origins.
    if (trimmed.startsWith("//") || trimmed.startsWith("/\\") || trimmed.startsWith("\\")) return false
    if (trimmed.startsWith("/")) return true
    return try {
        val target = java.net.URI(trimmed)
        val base = java.net.URI(origin)
        // Parse + compare scheme/host/port exactly. Prefix string match is bypassable by
        // "https://legit.com.evil.com" against origin "https://legit.com".
        target.userInfo == null &&
            target.host != null &&
            target.scheme.equals(base.scheme, ignoreCase = true) &&
            target.host.equals(base.host, ignoreCase = true) &&
            effectivePort(target) == effectivePort(base)
    } catch (_: Exception) {
        false
    }
}

private fun effectivePort(u: java.net.URI): Int =
    if (u.port != -1) {
        u.port
    } else if (u.scheme.equals("https", ignoreCase = true)) {
        443
    } else {
        80
    }
