package com.kauth.adapter.web.auth

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.OidcRequestBinding
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SocialLoginResult
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.util.Pkce
import com.kauth.domain.util.SecureTokens
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

// Matches AdminRoutes and PortalRoutes, the two flows that already bound their signed state.
private const val SOCIAL_STATE_MAX_AGE_MS = 300_000L

// Holds the csrfNonce of the state the redirect signed, so the callback can prove the browser
// presenting that state is the one the flow began in. Same idiom as KOTAUTH_ADMIN_PKCE.
private const val SOCIAL_STATE_COOKIE = "KOTAUTH_SOCIAL_STATE"

/**
 * The signed social-login state: `provider|slug|csrfNonce|timestampMillis|oidcNonce|
 * pkceVerifier|oauthParamsB64`.
 *
 * Written in one place and read in one place, both through here, because the field order has
 * grown twice and a reader left on an old index is a silent failure — the OAuth parameters would
 * simply come back empty and the login would still complete, just not as an OAuth flow.
 *
 * [oauthParamsB64] is base64url without padding and both binding values are base64url too, so no
 * field can contain the separator.
 *
 * [csrfNonce] is what binds the state to a user agent, and it only does that because /redirect
 * writes it to [SOCIAL_STATE_COOKIE] and /callback requires an exact match. Without that pairing
 * the signature proves only that *we* minted the state, not that this browser began the flow, and
 * an attacker who mints one at their leisure and feeds the victim the resulting callback URL gets
 * the victim signed in as them. The one-time authorization code, the PKCE verifier and the ID
 * token nonce do not close that: all three travel inside the state, so they bind the flow to
 * whoever holds it — which, since `signCookie` authenticates without encrypting, is anyone who
 * reads the callback URL, the IdP included.
 *
 * The payload is deliberately not single-use: it is age-bounded and the cookie is cleared on
 * completion. A server-side store would cost the property that any replica can complete any
 * callback.
 */
private class SocialState(
    val provider: String,
    val slug: String,
    val csrfNonce: String,
    val timestampMillis: Long,
    val binding: OidcRequestBinding,
    val oauthParamsB64: String,
) {
    fun toPayload(): String =
        listOf(
            provider,
            slug,
            csrfNonce,
            timestampMillis.toString(),
            binding.nonce,
            binding.codeVerifier,
            oauthParamsB64,
        ).joinToString("|")

    companion object {
        private const val FIELD_COUNT = 7

        fun parse(payload: String): SocialState? {
            val parts = payload.split("|")
            if (parts.size != FIELD_COUNT) return null
            val timestampMillis = parts[3].toLongOrNull() ?: return null
            return SocialState(
                provider = parts[0],
                slug = parts[1],
                csrfNonce = parts[2],
                timestampMillis = timestampMillis,
                binding = OidcRequestBinding(nonce = parts[4], codeVerifier = parts[5]),
                oauthParamsB64 = parts[6],
            )
        }
    }
}

/**
 * The cookie half of the state binding: `SameSite=Lax` because the callback arrives as a
 * top-level navigation from the IdP, which `Strict` would strip; host-scoped and path-scoped so
 * it reaches nothing but this tenant's social routes.
 */
private fun ApplicationCall.setSocialStateCookie(
    slug: String,
    value: String,
    secure: Boolean,
) = response.cookies.append(
    name = SOCIAL_STATE_COOKIE,
    value = value,
    maxAge = SOCIAL_STATE_MAX_AGE_MS / 1000,
    httpOnly = true,
    secure = secure,
    path = "/t/$slug/auth/social",
    extensions = mapOf("SameSite" to "Lax"),
)

private fun ApplicationCall.clearSocialStateCookie(
    slug: String,
    secure: Boolean,
) = response.cookies.append(
    name = SOCIAL_STATE_COOKIE,
    value = "",
    maxAge = 0L,
    httpOnly = true,
    secure = secure,
    path = "/t/$slug/auth/social",
    extensions = mapOf("SameSite" to "Lax"),
)

/**
 * Throttles one social-login request per IP and tenant, answering with the login page so a
 * throttled browser sees a page rather than a JSON body. Returns false once it has responded.
 */
private suspend fun ApplicationCall.allowSocialRequest(
    limiter: RateLimiterPort,
    slug: String,
    ctx: AuthTenantContext,
    tenant: Tenant?,
    identityProviderRepository: IdentityProviderRepository?,
): Boolean {
    if (limiter.isAllowed("social:${request.local.remoteAddress}:$slug")) return true
    val enabledProviders =
        if (tenant != null && identityProviderRepository != null) {
            identityProviderRepository.findEnabledByTenant(tenant.id).map { it.provider }
        } else {
            emptyList()
        }
    response.headers.append("Retry-After", "60")
    respondHtml(
        HttpStatusCode.TooManyRequests,
        AuthView.loginPage(
            tenantSlug = slug,
            ctx = ctx.viewContext,
            error = "Too many sign-in attempts. Please wait a moment and try again.",
            enabledProviders = enabledProviders,
            passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
            passkeysEnabled = tenant?.passkeysEnabled == true,
        ),
    )
    return false
}

private fun constantTimeEquals(
    a: String,
    b: String,
): Boolean =
    java.security.MessageDigest
        .isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

internal fun Route.socialLoginRoutes(
    oauthService: OAuthService,
    socialLoginService: SocialLoginService?,
    identityProviderRepository: IdentityProviderRepository?,
    encryptionService: EncryptionService,
    baseUrl: String,
    ssoTtlSeconds: Long,
    socialRateLimiter: RateLimiterPort,
) {
    get("/auth/social/{provider}/redirect") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant
        // Both halves of the flow reach an issuer over the network without any authentication
        // behind them, so an unthrottled loop here is an outbound-fetch amplifier.
        if (!call.allowSocialRequest(socialRateLimiter, slug, ctx, tenant, identityProviderRepository)) return@get
        val provName = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        // Any key the pattern accepts may be a configured OIDC provider. Whether this tenant has
        // one is the provider lookup's answer, not this guard's.
        val provider =
            ProviderKey.of(provName)
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported_provider"))

        if (socialLoginService == null) {
            return@get call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "social_login_not_configured"))
        }

        val oauthParams = call.request.queryParameters.toOAuthParams()

        val csrfNonce =
            java.util.UUID
                .randomUUID()
                .toString()
        // Generated per request, carried in the signed state, and read back in the callback: the
        // nonce binds the ID token to this request and the verifier binds the token exchange to it.
        val binding =
            OidcRequestBinding(
                nonce = SecureTokens.randomBase64Url(),
                codeVerifier = Pkce.newVerifier(),
            )
        val oauthParamsB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(oauthParams.toQueryString().toByteArray(Charsets.UTF_8))
        val statePayload =
            SocialState(
                provider = provider.value,
                slug = slug,
                csrfNonce = csrfNonce,
                timestampMillis = System.currentTimeMillis(),
                binding = binding,
                oauthParamsB64 = oauthParamsB64,
            ).toPayload()
        val signedState = encryptionService.signCookie(statePayload)

        val secure = baseUrl.startsWith("https://", ignoreCase = true)

        when (val result = socialLoginService.buildRedirectUrl(slug, provider, signedState, baseUrl, binding)) {
            is SocialLoginResult.Success -> {
                call.setSocialStateCookie(slug, encryptionService.signCookie(csrfNonce), secure)
                call.respondRedirect(result.value)
            }
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
                        ctx = ctx.viewContext,
                        error = result.error.toMessage(),
                        enabledProviders = enabledProviders,
                        passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                        passkeysEnabled = tenant?.passkeysEnabled == true,
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
        if (!call.allowSocialRequest(socialRateLimiter, slug, ctx, tenant, identityProviderRepository)) return@get
        val provName = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        // Any key the pattern accepts may be a configured OIDC provider. Whether this tenant has
        // one is the provider lookup's answer, not this guard's.
        val provider =
            ProviderKey.of(provName)
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
                    ctx = ctx.viewContext,
                    error = "Login with ${EnglishStrings.providerDisplayName(provider)} was cancelled or failed.",
                    enabledProviders = enabledProviders,
                    passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                    passkeysEnabled = tenant?.passkeysEnabled == true,
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
                    ctx = ctx.viewContext,
                    error = "Invalid or expired state parameter. Please try signing in again.",
                    enabledProviders = enabledProviders,
                    passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                    passkeysEnabled = tenant?.passkeysEnabled == true,
                ),
            )
            return@get
        }

        val socialState = SocialState.parse(verifiedPayload)
        val stateAgeMs = socialState?.let { System.currentTimeMillis() - it.timestampMillis }
        if (socialState == null ||
            socialState.provider != provider.value ||
            socialState.slug != slug ||
            stateAgeMs == null ||
            stateAgeMs > SOCIAL_STATE_MAX_AGE_MS
        ) {
            call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.loginPage(
                    tenantSlug = slug,
                    ctx = ctx.viewContext,
                    error = "State mismatch. Please try signing in again.",
                    enabledProviders = enabledProviders,
                    passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                    passkeysEnabled = tenant?.passkeysEnabled == true,
                ),
            )
            return@get
        }

        val secure = baseUrl.startsWith("https://", ignoreCase = true)

        // The signature says we minted this state; only the cookie says this browser began the
        // flow. Without it a state minted by an attacker, replayed at the victim, signs the victim
        // in as the attacker — every other guard on this path passes on an attacker-minted state.
        val boundNonce = call.request.cookies[SOCIAL_STATE_COOKIE]?.let { encryptionService.verifyCookie(it) }
        if (boundNonce == null || !constantTimeEquals(boundNonce, socialState.csrfNonce)) {
            call.clearSocialStateCookie(slug, secure)
            call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.loginPage(
                    tenantSlug = slug,
                    ctx = ctx.viewContext,
                    error = "This sign-in did not start in this browser. Please try signing in again.",
                    enabledProviders = enabledProviders,
                    passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                    passkeysEnabled = tenant?.passkeysEnabled == true,
                ),
            )
            return@get
        }
        call.clearSocialStateCookie(slug, secure)

        val oauthParamsRaw =
            try {
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(socialState.oauthParamsB64),
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
                    binding = socialState.binding,
                )
        ) {
            is SocialLoginResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.loginPage(
                        tenantSlug = slug,
                        ctx = ctx.viewContext,
                        error = result.error.toMessage(),
                        enabledProviders = enabledProviders,
                        passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                        passkeysEnabled = tenant?.passkeysEnabled == true,
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
                    val activeTenant =
                        tenant ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.completeAuthorizationCodeFlow(
                        slug = slug,
                        userId = loginSuccess.user.id!!,
                        tenantId = activeTenant.id,
                        oauthParams = restoredParams,
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
                                    enabledProviders = enabledProviders,
                                    passwordLoginEnabled = activeTenant.securityConfig.passwordLoginEnabled,
                                    passkeysEnabled = activeTenant.passkeysEnabled,
                                ),
                            )
                        },
                    )
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
                ctx = ctx.viewContext,
                providerName = EnglishStrings.providerDisplayName(pending.provider),
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
        val originatingClientId =
            parseQueryStringToOAuthParams(pending.oauthParamsRaw).clientId

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
                    originatingClientId = originatingClientId,
                )
        ) {
            is SocialLoginResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AuthView.socialRegistrationPage(
                        tenantSlug = slug,
                        ctx = ctx.viewContext,
                        providerName = EnglishStrings.providerDisplayName(pending.provider),
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
                        ctx = ctx.viewContext,
                        providerName = EnglishStrings.providerDisplayName(pending.provider),
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
                    call.completeAuthorizationCodeFlow(
                        slug = slug,
                        userId = loginSuccess.user.id!!,
                        tenantId = tenant.id,
                        oauthParams = restoredParams,
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
                                AuthView.socialRegistrationPage(
                                    tenantSlug = slug,
                                    ctx = ctx.viewContext,
                                    providerName = EnglishStrings.providerDisplayName(pending.provider),
                                    email = pending.email,
                                    error = message,
                                ),
                            )
                        },
                    )
                } else {
                    call.respondRedirect("/t/$slug/account/login")
                }
            }
        }
    }
}
