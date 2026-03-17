package com.kauth.adapter.web.auth

import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.IntrospectionResult
import com.kauth.domain.service.MfaError
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthError
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SelfServiceResult
import com.kauth.domain.service.SocialLoginError
import com.kauth.domain.service.SocialLoginNeedsRegistration
import com.kauth.domain.service.SocialLoginResult
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.domain.model.User
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.PortalClientProvisioning
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
    registerRateLimiter: RateLimiter,
    selfServiceService: UserSelfServiceService,
    mfaService: MfaService? = null,                           // Phase 3c — nullable for backward compat
    roleRepository: RoleRepository? = null,                   // Phase 1 fix — required_admins MFA check
    socialLoginService: SocialLoginService? = null,           // Phase 2 — Social Login
    identityProviderRepository: IdentityProviderRepository? = null,  // Phase 2 — load enabled providers
    baseUrl: String = ""                                      // Phase 2 — needed for callback URI
) {

    route("/t/{slug}") {

        // ------------------------------------------------------------------
        // Login — browser UI (updated for OAuth2 passthrough)
        // ------------------------------------------------------------------

        get("/login") {
            val slug          = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val registered    = call.request.queryParameters["registered"] == "true"

            // Preserve OAuth2 params from authorization endpoint redirect
            val oauthParams = call.request.queryParameters.toOAuthParams()

            // Phase 2: load enabled social providers for this tenant
            val enabledProviders = if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else emptyList()

            call.respondHtml(HttpStatusCode.OK, AuthView.loginPage(
                tenantSlug       = slug,
                theme            = theme,
                workspaceName    = workspaceName,
                success          = registered,
                oauthParams      = oauthParams,
                enabledProviders = enabledProviders
            ))
        }

        post("/login") {
            val slug          = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val enabledProviders = if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else emptyList()
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
                    AuthView.loginPage(slug, theme, workspaceName, error = "Too many login attempts. Please wait a moment and try again.", oauthParams = oauthParams, enabledProviders = enabledProviders)
                )
            }

            // Use authenticate() for both flows — it returns the User without issuing tokens
            val userAgent = call.request.headers["User-Agent"]
            when (val result = authService.authenticate(slug, username, password, ipAddress, userAgent)) {
                is AuthResult.Failure -> {
                    // Expired password gets a dedicated redirect so the user knows what to do
                    if (result.error is AuthError.PasswordExpired) {
                        call.respondRedirect("/t/$slug/forgot-password?reason=expired")
                    } else {
                        call.respondHtml(
                            HttpStatusCode.Unauthorized,
                            AuthView.loginPage(slug, theme, workspaceName, error = result.error.toMessage(), oauthParams = oauthParams, enabledProviders = enabledProviders)
                        )
                    }
                }
                is AuthResult.Success -> {
                    val user = result.value

                    // Phase 1 fix: enforce MFA enrollment requirement before the challenge check.
                    // If the tenant policy mandates MFA for this user (via "required" or
                    // "required_admins") and the user hasn't enrolled yet, block login with a
                    // clear error directing them to the user portal for enrollment.
                    //
                    // Exception: portal PKCE logins (client_id = kotauth-portal) are allowed
                    // through even when MFA isn't enrolled yet. The portal callback will detect
                    // the gap and redirect the user directly to the MFA setup page with a
                    // prominent notice, so they can enroll from within the portal itself.
                    val isPortalLogin = oauthParams.isOAuthFlow &&
                        oauthParams.clientId == PortalClientProvisioning.PORTAL_CLIENT_ID
                    if (mfaService != null && tenant != null && !isPortalLogin) {
                        val mfaPolicy = tenant.mfaPolicy
                        if (mfaPolicy != "optional") {
                            // Resolve effective roles only when the policy actually needs them —
                            // avoids an unnecessary DB query for the "required" (all-users) case.
                            val userRoles = if (mfaPolicy == "required_admins" && roleRepository != null) {
                                roleRepository.resolveEffectiveRoles(user.id!!, tenant.id)
                            } else emptyList()

                            if (mfaService.isMfaRequired(user, mfaPolicy, userRoles) && !mfaService.shouldChallengeMfa(user.id!!)) {
                                return@post call.respondHtml(
                                    HttpStatusCode.Forbidden,
                                    AuthView.loginPage(
                                        tenantSlug       = slug,
                                        theme            = theme,
                                        workspaceName    = workspaceName,
                                        error            = "Multi-factor authentication is required for your account. " +
                                                           "Please sign in to the user portal and enable MFA under Security settings.",
                                        oauthParams      = oauthParams,
                                        enabledProviders = enabledProviders
                                    )
                                )
                            }
                        }
                    }

                    // Phase 3c: MFA challenge — if user has MFA enrolled, redirect to challenge page
                    if (mfaService != null && mfaService.shouldChallengeMfa(user.id!!)) {
                        // Store a short-lived MFA pending token in an HMAC-signed cookie so we can
                        // complete the flow after the user enters their TOTP code.
                        // Signed with EncryptionService.signCookie() to prevent userId forgery
                        // (an unsigned cookie lets any client claim they passed MFA as any user).
                        val mfaPending = "${user.id}|$slug|${System.currentTimeMillis()}"
                        call.response.cookies.append(
                            name  = "KOTAUTH_MFA_PENDING",
                            value = EncryptionService.signCookie(mfaPending),
                            maxAge = 300L,   // 5 minutes
                            httpOnly = true,
                            path = "/t/$slug"
                        )
                        val queryString = if (oauthParams.isOAuthFlow) oauthParams.toQueryString() else ""
                        call.respondRedirect("/t/$slug/mfa-challenge$queryString")
                        return@post
                    }

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
                                    AuthView.loginPage(slug, theme, workspaceName, error = codeResult.error.toDescription(), oauthParams = oauthParams, enabledProviders = enabledProviders)
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
            val slug             = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant           = tenantRepository.findBySlug(slug)
            val theme            = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName    = tenant?.displayName ?: "KotAuth"
            val enabledProviders = if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else emptyList()
            call.respondHtml(HttpStatusCode.OK, AuthView.registerPage(slug, theme, workspaceName, enabledProviders = enabledProviders))
        }

        post("/register") {
            val slug             = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant           = tenantRepository.findBySlug(slug)
            val theme            = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName    = tenant?.displayName ?: "KotAuth"
            val enabledProviders = if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else emptyList()
            val ipAddress        = call.request.local.remoteAddress

            // Rate limiting on registration
            val rateLimitKey = "register:$ipAddress"
            if (!registerRateLimiter.isAllowed(rateLimitKey)) {
                return@post call.respondHtml(
                    HttpStatusCode.TooManyRequests,
                    AuthView.registerPage(slug, theme, workspaceName,
                        error            = "Too many registration attempts. Please wait a moment.",
                        enabledProviders = enabledProviders)
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
                        AuthView.registerPage(slug, theme, workspaceName,
                            error            = result.error.toMessage(),
                            prefill          = prefill,
                            enabledProviders = enabledProviders)
                    )
            }
        }

        // ------------------------------------------------------------------
        // Forgot password — request a reset link
        // ------------------------------------------------------------------

        get("/forgot-password") {
            val slug          = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val sent          = call.request.queryParameters["sent"] == "true"
            // reason=expired: shown when the user is redirected from login due to an expired password
            val reason = call.request.queryParameters["reason"]
            val errorMsg = if (reason == "expired")
                "Your password has expired. Enter your email below to receive a reset link."
            else null
            call.respondHtml(HttpStatusCode.OK, AuthView.forgotPasswordPage(slug, theme, workspaceName, error = errorMsg, sent = sent))
        }

        post("/forgot-password") {
            val slug      = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val params    = call.receiveParameters()
            val email     = params["email"]?.trim() ?: ""
            val ipAddress = call.request.local.remoteAddress
            val baseUrl   = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }

            // Rate-limit to prevent email flooding
            val rateLimitKey = "forgot:$ipAddress"
            if (!registerRateLimiter.isAllowed(rateLimitKey)) {
                // Even on rate-limit, redirect to sent page — don't leak timing
                return@post call.respondRedirect("/t/$slug/forgot-password?sent=true")
            }

            // Always fires and always redirects to the "sent" page — never reveals
            // whether the email exists. Any SMTP errors are swallowed by the service.
            selfServiceService.initiateForgotPassword(email, slug, baseUrl, ipAddress)
            call.respondRedirect("/t/$slug/forgot-password?sent=true")
        }

        // ------------------------------------------------------------------
        // Reset password — consume the token from the email link
        // ------------------------------------------------------------------

        get("/reset-password") {
            val slug          = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val token         = call.request.queryParameters["token"] ?: ""

            if (token.isBlank()) {
                return@get call.respondRedirect("/t/$slug/forgot-password")
            }
            call.respondHtml(HttpStatusCode.OK, AuthView.resetPasswordPage(slug, theme, workspaceName, token = token))
        }

        post("/reset-password") {
            val slug            = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant          = tenantRepository.findBySlug(slug)
            val theme           = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName   = tenant?.displayName ?: "KotAuth"
            val params          = call.receiveParameters()
            val token           = params["token"] ?: ""
            val newPassword     = params["new_password"] ?: ""
            val confirmPassword = params["confirm_password"] ?: ""

            when (val result = selfServiceService.confirmPasswordReset(token, newPassword, confirmPassword)) {
                is SelfServiceResult.Success ->
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AuthView.resetPasswordPage(slug, theme, workspaceName, token = token, success = true)
                    )
                is SelfServiceResult.Failure ->
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AuthView.resetPasswordPage(slug, theme, workspaceName, token = token, error = result.error.message)
                    )
            }
        }

        // ------------------------------------------------------------------
        // Email verification — clicked from the verification email link
        // ------------------------------------------------------------------

        get("/verify-email") {
            val slug          = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val token         = call.request.queryParameters["token"] ?: ""

            if (token.isBlank()) {
                return@get call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.verifyEmailPage(slug, theme, workspaceName, success = false, message = "Verification link is missing or invalid.")
                )
            }

            when (val result = selfServiceService.confirmEmailVerification(token)) {
                is SelfServiceResult.Success ->
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AuthView.verifyEmailPage(slug, theme, workspaceName, success = true, message = "Your email address has been verified successfully.")
                    )
                is SelfServiceResult.Failure ->
                    call.respondHtml(
                        HttpStatusCode.BadRequest,
                        AuthView.verifyEmailPage(slug, theme, workspaceName, success = false, message = result.error.message)
                    )
            }
        }

        // ------------------------------------------------------------------
        // MFA Challenge — Phase 3c TOTP verification during login
        // ------------------------------------------------------------------

        get("/mfa-challenge") {
            val slug          = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val oauthParams   = call.request.queryParameters.toOAuthParams()

            // Verify MFA pending cookie — must be present and signature must be valid
            val rawPendingGet = call.request.cookies["KOTAUTH_MFA_PENDING"]
            if (rawPendingGet.isNullOrBlank() || EncryptionService.verifyCookie(rawPendingGet) == null) {
                return@get call.respondRedirect("/t/$slug/login")
            }

            call.respondHtml(HttpStatusCode.OK, AuthView.mfaChallengePage(slug, theme, workspaceName, oauthParams = oauthParams))
        }

        post("/mfa-challenge") {
            val slug          = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val params    = call.receiveParameters()
            val code      = params["code"]?.trim() ?: ""
            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]
            val oauthParams = params.toOAuthParams()

            // Verify and parse MFA pending cookie — signature check prevents forgery
            val rawCookie = call.request.cookies["KOTAUTH_MFA_PENDING"]
            if (rawCookie.isNullOrBlank()) {
                return@post call.respondRedirect("/t/$slug/login")
            }
            val pending = EncryptionService.verifyCookie(rawCookie)
            if (pending == null) {
                // Signature invalid — tampered cookie, force re-login
                return@post call.respondRedirect("/t/$slug/login")
            }
            val parts = pending.split("|")
            if (parts.size != 3) {
                return@post call.respondRedirect("/t/$slug/login")
            }
            val userId    = parts[0].toIntOrNull() ?: return@post call.respondRedirect("/t/$slug/login")
            val timestamp = parts[2].toLongOrNull() ?: return@post call.respondRedirect("/t/$slug/login")

            // Check expiry (5 minutes)
            if (System.currentTimeMillis() - timestamp > 300_000) {
                return@post call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.mfaChallengePage(slug, theme, workspaceName, error = "MFA challenge expired. Please log in again.", oauthParams = oauthParams)
                )
            }

            if (mfaService == null) {
                return@post call.respondRedirect("/t/$slug/login")
            }

            // Try TOTP code first, then recovery code
            val mfaResult = if (code.length == 6 && code.all { it.isDigit() }) {
                mfaService.verifyTotp(userId, code)
            } else {
                mfaService.verifyRecoveryCode(userId, code)
            }

            when (mfaResult) {
                is MfaResult.Failure -> {
                    call.respondHtml(
                        HttpStatusCode.Unauthorized,
                        AuthView.mfaChallengePage(slug, theme, workspaceName, error = "Invalid code. Please try again.", oauthParams = oauthParams)
                    )
                }
                is MfaResult.Success -> {
                    // Clear MFA pending cookie
                    call.response.cookies.append(
                        name     = "KOTAUTH_MFA_PENDING",
                        value    = "",
                        maxAge   = 0L,
                        path     = "/t/$slug",
                        httpOnly = true
                    )

                    if (oauthParams.isOAuthFlow) {
                        val clientId    = oauthParams.clientId ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing client_id")
                        val redirectUri = oauthParams.redirectUri ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing redirect_uri")

                        when (val codeResult = oauthService.issueAuthorizationCode(
                            tenantSlug          = slug,
                            userId              = userId,
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
                                val authCode = codeResult.value.code
                                val state    = oauthParams.state
                                val redirect = buildString {
                                    append(redirectUri)
                                    append("?code=").append(authCode)
                                    if (!state.isNullOrBlank()) append("&state=").append(state)
                                }
                                call.respondRedirect(redirect)
                            }
                            is OAuthResult.Failure -> {
                                call.respondHtml(HttpStatusCode.BadRequest,
                                    AuthView.mfaChallengePage(slug, theme, workspaceName, error = codeResult.error.toDescription(), oauthParams = oauthParams))
                            }
                        }
                    } else {
                        // Direct login — complete the token issuance
                        // We need to re-authenticate to get tokens since we only stored userId
                        call.respond(mapOf(
                            "message" to "MFA verification successful",
                            "user_id" to userId
                        ))
                    }
                }
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
        // Social Login — Phase 2
        //
        // GET  /auth/social/{provider}/redirect  — initiate OAuth2 flow
        // GET  /auth/social/{provider}/callback  — receive code from provider
        //
        // CSRF protection: state parameter is HMAC-signed via EncryptionService.signCookie().
        // Format: "{provider}|{slug}|{nonce}|{oauthParamsBase64}"
        // On callback, signature is verified before processing the code.
        // ==================================================================

        get("/auth/social/{provider}/redirect") {
            val slug     = call.parameters["slug"]     ?: return@get call.respond(HttpStatusCode.BadRequest)
            val provName = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val provider = SocialProvider.fromValueOrNull(provName)
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported_provider"))

            if (socialLoginService == null) {
                return@get call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "social_login_not_configured"))
            }

            val oauthParams = call.request.queryParameters.toOAuthParams()

            // Build CSRF state: sign provider|slug|nonce|oauthParamsBase64
            val nonce           = java.util.UUID.randomUUID().toString()
            val oauthParamsB64  = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(oauthParams.toQueryString().toByteArray(Charsets.UTF_8))
            val statePayload    = "${provider.value}|$slug|$nonce|$oauthParamsB64"
            val signedState     = EncryptionService.signCookie(statePayload)

            when (val result = socialLoginService.buildRedirectUrl(slug, provider, signedState, baseUrl)) {
                is SocialLoginResult.Success -> call.respondRedirect(result.value)
                is SocialLoginResult.Failure -> {
                    val tenant        = tenantRepository.findBySlug(slug)
                    val theme         = tenant?.theme ?: TenantTheme.DEFAULT
                    val workspaceName = tenant?.displayName ?: "KotAuth"
                    val enabledProviders = if (tenant != null && identityProviderRepository != null) {
                        identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
                    } else emptyList()
                    call.respondHtml(
                        HttpStatusCode.BadRequest,
                        AuthView.loginPage(
                            tenantSlug       = slug,
                            theme            = theme,
                            workspaceName    = workspaceName,
                            error            = result.error.toMessage(),
                            enabledProviders = enabledProviders
                        )
                    )
                }
                // buildRedirectUrl never returns NeedsRegistration — that is a callback-only
                // result. This branch satisfies the exhaustive-when requirement.
                is SocialLoginResult.NeedsRegistration -> call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "internal_error")
                )
            }
        }

        get("/auth/social/{provider}/callback") {
            val slug     = call.parameters["slug"]     ?: return@get call.respond(HttpStatusCode.BadRequest)
            val provName = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val provider = SocialProvider.fromValueOrNull(provName)
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported_provider"))

            val tenant        = tenantRepository.findBySlug(slug)
            val theme         = tenant?.theme ?: TenantTheme.DEFAULT
            val workspaceName = tenant?.displayName ?: "KotAuth"
            val enabledProviders = if (tenant != null && identityProviderRepository != null) {
                identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
            } else emptyList()

            if (socialLoginService == null) {
                return@get call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "social_login_not_configured"))
            }

            val code  = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"]
            val error = call.request.queryParameters["error"]

            // Provider-returned error (e.g. user denied access)
            if (!error.isNullOrBlank()) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.loginPage(
                        tenantSlug       = slug,
                        theme            = theme,
                        workspaceName    = workspaceName,
                        error            = "Login with ${provider.displayName} was cancelled or failed.",
                        enabledProviders = enabledProviders
                    )
                )
                return@get
            }

            if (code.isNullOrBlank() || state.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_code_or_state"))
                return@get
            }

            // Verify CSRF state signature
            val verifiedPayload = EncryptionService.verifyCookie(state)
            if (verifiedPayload == null) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.loginPage(
                        tenantSlug       = slug,
                        theme            = theme,
                        workspaceName    = workspaceName,
                        error            = "Invalid or expired state parameter. Please try signing in again.",
                        enabledProviders = enabledProviders
                    )
                )
                return@get
            }

            // Parse state: {provider}|{slug}|{nonce}|{oauthParamsBase64}
            val parts = verifiedPayload.split("|")
            if (parts.size < 4 || parts[0] != provider.value || parts[1] != slug) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.loginPage(
                        tenantSlug       = slug,
                        theme            = theme,
                        workspaceName    = workspaceName,
                        error            = "State mismatch. Please try signing in again.",
                        enabledProviders = enabledProviders
                    )
                )
                return@get
            }

            // Recover the original OAuth params (if any) from the state
            val oauthParamsRaw = try {
                String(java.util.Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8)
            } catch (_: Exception) { "" }
            // Parse them back into OAuthParams
            val restoredParams = parseQueryStringToOAuthParams(oauthParamsRaw)

            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]

            when (val result = socialLoginService.handleCallback(
                tenantSlug = slug,
                provider   = provider,
                code       = code,
                baseUrl    = baseUrl,
                ipAddress  = ipAddress,
                userAgent  = userAgent
            )) {
                is SocialLoginResult.Failure -> {
                    call.respondHtml(
                        HttpStatusCode.BadRequest,
                        AuthView.loginPage(
                            tenantSlug       = slug,
                            theme            = theme,
                            workspaceName    = workspaceName,
                            error            = result.error.toMessage(),
                            enabledProviders = enabledProviders
                        )
                    )
                }
                is SocialLoginResult.NeedsRegistration -> {
                    // No existing account — store provider profile in a short-lived signed cookie
                    // and redirect to the registration completion page.
                    val pending = result.data
                    val cookieVal = EncryptionService.signCookie(
                        buildSocialPendingPayload(pending, slug, oauthParamsRaw)
                    )
                    call.response.cookies.append(
                        name     = "KOTAUTH_SOCIAL_PENDING",
                        value    = cookieVal,
                        maxAge   = 600L,
                        httpOnly = true,
                        path     = "/t/$slug/auth/social"
                    )
                    call.respondRedirect("/t/$slug/auth/social/complete-registration")
                }
                is SocialLoginResult.Success -> {
                    val loginSuccess = result.value
                    if (restoredParams.isOAuthFlow) {
                        // Authorization Code Flow — issue code and redirect
                        val clientId    = restoredParams.clientId ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val redirectUri = restoredParams.redirectUri ?: return@get call.respond(HttpStatusCode.BadRequest)
                        when (val codeResult = oauthService.issueAuthorizationCode(
                            tenantSlug          = slug,
                            userId              = loginSuccess.user.id!!,
                            clientId            = clientId,
                            redirectUri         = redirectUri,
                            scopes              = restoredParams.scope ?: "openid",
                            codeChallenge       = restoredParams.codeChallenge,
                            codeChallengeMethod = restoredParams.codeChallengeMethod,
                            nonce               = restoredParams.nonce,
                            state               = restoredParams.state,
                            ipAddress           = ipAddress
                        )) {
                            is OAuthResult.Success -> {
                                val authCode   = codeResult.value.code
                                val stateParam = restoredParams.state
                                val redirect   = buildString {
                                    append(redirectUri)
                                    append("?code=").append(authCode)
                                    if (!stateParam.isNullOrBlank()) append("&state=").append(stateParam)
                                }
                                call.respondRedirect(redirect)
                            }
                            is OAuthResult.Failure -> call.respondHtml(
                                HttpStatusCode.BadRequest,
                                AuthView.loginPage(
                                    tenantSlug       = slug,
                                    theme            = theme,
                                    workspaceName    = workspaceName,
                                    error            = codeResult.error.toDescription(),
                                    enabledProviders = enabledProviders
                                )
                            )
                        }
                    } else {
                        // Direct flow — return tokens as JSON
                        call.respond(loginSuccess.tokens)
                    }
                }
            }
        }

        // ==================================================================
        // Social Registration Completion
        //
        // GET  /auth/social/complete-registration  — show username choice form
        // POST /auth/social/complete-registration  — create account + log in
        //
        // The provider profile is carried in a short-lived HMAC-signed cookie
        // (KOTAUTH_SOCIAL_PENDING, 10 min TTL) set by the callback handler when
        // NeedsRegistration is returned. No server-side session needed.
        // ==================================================================

        get("/auth/social/complete-registration") {
            val slug   = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val theme  = tenant.theme
            val workspaceName = tenant.displayName

            val rawCookie = call.request.cookies["KOTAUTH_SOCIAL_PENDING"]
            val pending   = parseSocialPendingCookie(rawCookie)

            if (pending == null) {
                // Cookie missing, expired, or tampered — send back to login
                return@get call.respondRedirect("/t/$slug/login?error=${encodeParam("Session expired. Please sign in again.")}")
            }

            val suggestedUsername = pending.email
                .substringBefore("@")
                .replace(Regex("[^a-zA-Z0-9_]"), "")
                .lowercase()
                .take(32)
                .ifBlank { "user" }

            call.respondHtml(HttpStatusCode.OK, AuthView.socialRegistrationPage(
                tenantSlug      = slug,
                theme           = theme,
                workspaceName   = workspaceName,
                providerName    = pending.provider.displayName,
                email           = pending.email,
                prefillUsername = suggestedUsername,
                prefillFullName = pending.name ?: ""
            ))
        }

        post("/auth/social/complete-registration") {
            val slug   = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val theme  = tenant.theme
            val workspaceName = tenant.displayName

            val rawCookie = call.request.cookies["KOTAUTH_SOCIAL_PENDING"]
            val pending   = parseSocialPendingCookie(rawCookie)

            if (pending == null) {
                return@post call.respondRedirect("/t/$slug/login?error=${encodeParam("Session expired. Please sign in again.")}")
            }

            if (socialLoginService == null) {
                return@post call.respond(HttpStatusCode.NotImplemented)
            }

            val params        = call.receiveParameters()
            val chosenUsername = params["username"]?.trim() ?: ""
            val chosenFullName = params["full_name"]?.trim()

            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]

            when (val result = socialLoginService.completeSocialRegistration(
                tenantSlug     = slug,
                provider       = pending.provider,
                providerUserId = pending.providerUserId,
                email          = pending.email,
                providerName   = chosenFullName?.ifBlank { null } ?: pending.name,
                avatarUrl      = pending.avatarUrl,
                emailVerified  = pending.emailVerified,
                chosenUsername = chosenUsername,
                ipAddress      = ipAddress,
                userAgent      = userAgent
            )) {
                is SocialLoginResult.Failure -> {
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AuthView.socialRegistrationPage(
                            tenantSlug      = slug,
                            theme           = theme,
                            workspaceName   = workspaceName,
                            providerName    = pending.provider.displayName,
                            email           = pending.email,
                            prefillUsername = chosenUsername,
                            prefillFullName = chosenFullName ?: pending.name ?: "",
                            error           = result.error.toMessage()
                        )
                    )
                }
                is SocialLoginResult.NeedsRegistration -> {
                    // Should not happen from completeSocialRegistration — treat as internal error
                    call.respondHtml(
                        HttpStatusCode.InternalServerError,
                        AuthView.socialRegistrationPage(
                            tenantSlug    = slug,
                            theme         = theme,
                            workspaceName = workspaceName,
                            providerName  = pending.provider.displayName,
                            email         = pending.email,
                            error         = "An unexpected error occurred. Please try again."
                        )
                    )
                }
                is SocialLoginResult.Success -> {
                    // Clear the pending cookie — single-use
                    call.response.cookies.append(
                        name     = "KOTAUTH_SOCIAL_PENDING",
                        value    = "",
                        maxAge   = 0L,
                        httpOnly = true,
                        path     = "/t/$slug/auth/social"
                    )
                    val loginSuccess = result.value
                    val restoredParams = parseQueryStringToOAuthParams(pending.oauthParamsRaw)

                    if (restoredParams.isOAuthFlow) {
                        val clientId    = restoredParams.clientId ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val redirectUri = restoredParams.redirectUri ?: return@post call.respond(HttpStatusCode.BadRequest)
                        when (val codeResult = oauthService.issueAuthorizationCode(
                            tenantSlug          = slug,
                            userId              = loginSuccess.user.id!!,
                            clientId            = clientId,
                            redirectUri         = redirectUri,
                            scopes              = restoredParams.scope ?: "openid",
                            codeChallenge       = restoredParams.codeChallenge,
                            codeChallengeMethod = restoredParams.codeChallengeMethod,
                            nonce               = restoredParams.nonce,
                            state               = restoredParams.state,
                            ipAddress           = ipAddress
                        )) {
                            is OAuthResult.Success -> {
                                val authCode   = codeResult.value.code
                                val stateParam = restoredParams.state
                                val redirect   = buildString {
                                    append(redirectUri)
                                    append("?code=").append(authCode)
                                    if (!stateParam.isNullOrBlank()) append("&state=").append(stateParam)
                                }
                                call.respondRedirect(redirect)
                            }
                            is OAuthResult.Failure -> call.respondHtml(
                                HttpStatusCode.BadRequest,
                                AuthView.socialRegistrationPage(
                                    tenantSlug    = slug,
                                    theme         = theme,
                                    workspaceName = workspaceName,
                                    providerName  = pending.provider.displayName,
                                    email         = pending.email,
                                    error         = codeResult.error.toDescription()
                                )
                            )
                        }
                    } else {
                        // No OAuth flow context (direct browser visit to login page) —
                        // redirect to portal login which will re-initiate a fresh PKCE flow.
                        call.respondRedirect("/t/$slug/account/login")
                    }
                }
            }
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

            val enabledProviders = identityProviderRepository
                ?.findEnabledByTenant(tenant.id)?.map { it.provider } ?: emptyList()

            call.respondHtml(HttpStatusCode.OK, AuthView.loginPage(
                tenantSlug       = slug,
                theme            = tenant.theme,
                workspaceName    = tenant.displayName,
                oauthParams      = oauthParams,
                enabledProviders = enabledProviders
            ))
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
    is AuthError.PasswordExpired      -> "Your password has expired. Please reset it."
}

private fun SocialLoginError.toMessage(): String = when (this) {
    is SocialLoginError.TenantNotFound        -> "Tenant not found."
    is SocialLoginError.ProviderNotConfigured -> "Social login with this provider is not configured for this tenant."
    is SocialLoginError.EmailNotProvided      -> "Your social account did not provide an email address. Please use username/password login or grant email access."
    is SocialLoginError.UserDisabled          -> "Your account has been disabled."
    is SocialLoginError.AccountCreationFailed -> "Failed to create an account. Please try again or contact support."
    is SocialLoginError.RegistrationDisabled  -> "Account registration is not enabled for this workspace."
    is SocialLoginError.UsernameConflict      -> "That username is already taken. Please choose a different one."
    is SocialLoginError.InvalidUsername       -> this.reason
    is SocialLoginError.ProviderError         -> "An error occurred communicating with the identity provider. Please try again."
    is SocialLoginError.InternalError         -> "An internal error occurred. Please try again."
}

// ============================================================================
// Social pending registration cookie helpers
// ============================================================================

/**
 * Parsed contents of the KOTAUTH_SOCIAL_PENDING cookie.
 * Holds everything needed to complete a social registration across the
 * GET → POST round-trip, without touching server-side state.
 */
private data class SocialPendingData(
    val provider       : com.kauth.domain.model.SocialProvider,
    val slug           : String,
    val providerUserId : String,
    val email          : String,
    val name           : String?,
    val avatarUrl      : String?,
    val emailVerified  : Boolean,
    val oauthParamsRaw : String   // raw query string, empty string when no OAuth context
)

/**
 * Encodes a [SocialLoginNeedsRegistration] into the HMAC-signed cookie payload.
 * Format (pipe-separated, variable fields are base64url-encoded):
 *   provider | slug | providerUserId_b64 | email_b64 | name_b64 | avatarUrl_b64 | emailVerified | oauthParamsRaw_b64 | timestamp
 */
private fun buildSocialPendingPayload(
    data           : SocialLoginNeedsRegistration,
    slug           : String,
    oauthParamsRaw : String
): String {
    val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    fun String?.b64() = enc.encodeToString((this ?: "").toByteArray(Charsets.UTF_8))
    return listOf(
        data.provider.value,
        slug,
        data.providerUserId.b64(),
        data.email.b64(),
        data.name.b64(),
        data.avatarUrl.b64(),
        data.emailVerified.toString(),
        oauthParamsRaw.b64(),
        System.currentTimeMillis().toString()
    ).joinToString("|")
}

/**
 * Verifies and parses the KOTAUTH_SOCIAL_PENDING cookie.
 * Returns null if the cookie is missing, invalid, tampered, or expired (> 10 min).
 */
private fun parseSocialPendingCookie(rawCookie: String?): SocialPendingData? {
    if (rawCookie.isNullOrBlank()) return null
    val payload = EncryptionService.verifyCookie(rawCookie) ?: return null
    val parts   = payload.split("|")
    if (parts.size < 9) return null

    val timestamp = parts[8].toLongOrNull() ?: return null
    if (System.currentTimeMillis() - timestamp > 600_000) return null  // 10-minute TTL

    return try {
        val dec      = java.util.Base64.getUrlDecoder()
        fun decode(s: String) = String(dec.decode(s), Charsets.UTF_8)

        val provider       = com.kauth.domain.model.SocialProvider.fromValueOrNull(parts[0]) ?: return null
        val slug           = parts[1]
        val providerUserId = decode(parts[2])
        val email          = decode(parts[3])
        val name           = decode(parts[4]).ifBlank { null }
        val avatarUrl      = decode(parts[5]).ifBlank { null }
        val emailVerified  = parts[6].toBooleanStrictOrNull() ?: false
        val oauthParamsRaw = decode(parts[7])

        if (email.isBlank() || providerUserId.isBlank()) return null

        SocialPendingData(
            provider       = provider,
            slug           = slug,
            providerUserId = providerUserId,
            email          = email,
            name           = name,
            avatarUrl      = avatarUrl,
            emailVerified  = emailVerified,
            oauthParamsRaw = oauthParamsRaw
        )
    } catch (_: Exception) {
        null
    }
}

private fun encodeParam(value: String) =
    java.net.URLEncoder.encode(value, "UTF-8")

/**
 * Parses a query string (with or without a leading '?') back into [AuthView.OAuthParams].
 * Used during the social callback to restore the original OAuth2 parameters from state.
 *
 * NOTE: OAuthParams.toQueryString() includes a leading '?' when non-empty. This function
 * strips it so the first parameter key is not parsed as "?response_type" instead of
 * "response_type" — which would cause isOAuthFlow to return false and silently break the
 * portal social login redirect-back-to-callback path.
 */
private fun parseQueryStringToOAuthParams(qs: String): AuthView.OAuthParams {
    if (qs.isBlank()) return AuthView.OAuthParams()
    val normalized = if (qs.startsWith("?")) qs.substring(1) else qs
    if (normalized.isBlank()) return AuthView.OAuthParams()
    val map = normalized.split("&").mapNotNull {
        val idx = it.indexOf('=')
        if (idx < 0) null else {
            val k = java.net.URLDecoder.decode(it.substring(0, idx), "UTF-8")
            val v = java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            k to v
        }
    }.toMap()
    return AuthView.OAuthParams(
        responseType        = map["response_type"],
        clientId            = map["oauth_client_id"] ?: map["client_id"],
        redirectUri         = map["redirect_uri"],
        scope               = map["scope"],
        state               = map["state"],
        codeChallenge       = map["code_challenge"],
        codeChallengeMethod = map["code_challenge_method"],
        nonce               = map["nonce"]
    )
}
