package com.kauth.adapter.web.auth

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BrokeredSignInFailure
import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.AuditLogPort
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
import io.ktor.server.plugins.origin
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
//
// The name carries both the provider and the tenant slug because one name shared by two flows
// means the second of them begun in one browser overwrites the first, and the first callback then
// fails to bind. Since `__Host-` forces `Path=/`, the name is all that separates them.
private const val SOCIAL_STATE_COOKIE = "KOTAUTH_SOCIAL_STATE"

// The registration leg's half of the flow: the pending profile, and the nonce that binds it to
// the browser the callback minted it in — the same pairing as the state and its cookie.
private const val SOCIAL_PENDING_COOKIE = "KOTAUTH_SOCIAL_PENDING"
private const val SOCIAL_PENDING_BINDING_COOKIE = "KOTAUTH_SOCIAL_PENDING_BINDING"
private const val SOCIAL_PENDING_MAX_AGE_MS = 600_000L

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
 * The wire name of a social cookie for [slug]: `__Host-` prefixed on https, bare over plain http,
 * and suffixed with the tenant either way.
 *
 * `__Host-` forbids `Domain`, which is exactly the property a sibling subdomain needs to overwrite
 * a host-only cookie — a server cannot tell the two apart, and browsers enforce the prefix where
 * it cannot. The prefix also forces `Path=/`, so path scoping is what it costs: without the slug
 * in the name, (host, name, path) would be identical for every tenant and one tenant's in-flight
 * flow would clobber another's. The name now carries what the path used to. Over plain http the
 * prefix requires `Secure` and would be dropped silently, so `make run` on localhost keeps the
 * bare name; the slug stays there too so both connections have one cookie shape.
 *
 * A slug is `[a-z0-9-]+` and a provider key `[a-z0-9-]{1,32}`, so neither can contain the `_`
 * that joins them and no two (tenant, provider) pairs can collide on one name.
 */
private fun socialCookieName(
    base: String,
    slug: String,
    secure: Boolean,
) = if (secure) "__Host-${base}_$slug" else "${base}_$slug"

private fun socialStateCookieName(
    provider: ProviderKey,
    slug: String,
    secure: Boolean,
) = socialCookieName("${SOCIAL_STATE_COOKIE}_${provider.value}", slug, secure)

private fun socialCookiePath(
    slug: String,
    secure: Boolean,
) = if (secure) "/" else "/t/$slug/auth/social"

/**
 * The cookie half of the state binding: `SameSite=Lax` because the callback arrives as a
 * top-level navigation from the IdP, which `Strict` would strip; host-scoped and path-scoped so
 * it reaches nothing but this tenant's social routes.
 */
private fun ApplicationCall.setSocialStateCookie(
    slug: String,
    provider: ProviderKey,
    value: String,
    secure: Boolean,
) = response.cookies.append(
    name = socialStateCookieName(provider, slug, secure),
    value = value,
    maxAge = SOCIAL_STATE_MAX_AGE_MS / 1000,
    httpOnly = true,
    secure = secure,
    path = socialCookiePath(slug, secure),
    extensions = mapOf("SameSite" to "Lax"),
)

private fun ApplicationCall.clearSocialStateCookie(
    slug: String,
    provider: ProviderKey,
    secure: Boolean,
) = response.cookies.append(
    name = socialStateCookieName(provider, slug, secure),
    value = "",
    maxAge = 0L,
    httpOnly = true,
    secure = secure,
    path = socialCookiePath(slug, secure),
    extensions = mapOf("SameSite" to "Lax"),
)

/**
 * Writes the pending-registration pair: the signed profile, and the signed nonce that binds it to
 * this browser. Hardened like the state cookie — the two halves of one flow disagreeing on cookie
 * attributes is how the weaker one comes to look deliberate.
 */
private fun ApplicationCall.setSocialPendingCookies(
    slug: String,
    signedPending: String,
    signedNonce: String,
    secure: Boolean,
) {
    listOf(
        SOCIAL_PENDING_COOKIE to signedPending,
        SOCIAL_PENDING_BINDING_COOKIE to signedNonce,
    ).forEach { (name, value) ->
        response.cookies.append(
            name = socialCookieName(name, slug, secure),
            value = value,
            maxAge = SOCIAL_PENDING_MAX_AGE_MS / 1000,
            httpOnly = true,
            secure = secure,
            path = socialCookiePath(slug, secure),
            extensions = mapOf("SameSite" to "Lax"),
        )
    }
}

private fun ApplicationCall.clearSocialPendingCookies(
    slug: String,
    secure: Boolean,
) {
    listOf(SOCIAL_PENDING_COOKIE, SOCIAL_PENDING_BINDING_COOKIE).forEach { name ->
        response.cookies.append(
            name = socialCookieName(name, slug, secure),
            value = "",
            maxAge = 0L,
            httpOnly = true,
            secure = secure,
            path = socialCookiePath(slug, secure),
            extensions = mapOf("SameSite" to "Lax"),
        )
    }
}

/**
 * The pending registration this request may act on: minted for this tenant, in this browser.
 *
 * The signature proves only that we minted the cookie. Without the slug check, whoever administers
 * any tenant on the instance can point it at an IdP they control, have it assert an address they
 * do not own, and replay the resulting cookie at that address's tenant to be handed its owner's
 * account. Cookie path scoping is browser-side only and stops no non-browser client. The nonce is
 * the other half: a cookie planted in someone else's browser must not complete as if they had
 * begun the flow.
 */
private fun ApplicationCall.readSocialPending(
    slug: String,
    secure: Boolean,
    encryptionService: EncryptionService,
): SocialPendingData? {
    val rawPending = request.cookies[socialCookieName(SOCIAL_PENDING_COOKIE, slug, secure)]
    val pending = parseSocialPendingCookie(rawPending, encryptionService) ?: return null
    if (!constantTimeEquals(pending.slug, slug)) return null
    val boundNonce =
        request.cookies[socialCookieName(SOCIAL_PENDING_BINDING_COOKIE, slug, secure)]
            ?.let { encryptionService.verifyCookie(it) } ?: return null
    return pending.takeIf { constantTimeEquals(boundNonce, it.csrfNonce) }
}

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
    // `local` is the raw connection point, which behind the shipped Caddy topology is the proxy
    // for every request — one deployment-wide budget. `origin` is the form XForwardedHeaders feeds.
    // `remoteAddress`, never `remoteHost`: Netty's `remoteHost` getter calls
    // InetSocketAddress.getHostName(), an uncached blocking reverse-DNS lookup on the event loop, so
    // an unauthenticated endpoint would resolve a PTR record before deciding whether to shed the
    // request — the limiter cannot shed the flood it exists for. XForwardedHeaders feeds both.
    if (limiter.isAllowed("social:${request.origin.remoteAddress}:$slug")) return true
    val enabledProviders =
        if (tenant != null && identityProviderRepository != null) {
            identityProviderRepository.findEnabledByTenant(tenant.id).asLoginProviders()
        } else {
            emptyList()
        }
    response.headers.append("Retry-After", "60")
    // Tenant may be unresolved (unknown slug); USERNAME is the safe, mode-agnostic fallback.
    val loginIdentifierMode = tenant?.securityConfig?.loginIdentifierMode ?: LoginIdentifierMode.USERNAME
    respondHtml(
        HttpStatusCode.TooManyRequests,
        AuthView.loginPage(
            tenantSlug = slug,
            ctx = ctx.viewContext,
            error = "Too many sign-in attempts. Please wait a moment and try again.",
            enabledProviders = enabledProviders,
            passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
            passkeysEnabled = tenant?.passkeysEnabled == true,
            loginIdentifierMode = loginIdentifierMode,
        ),
    )
    return false
}

/**
 * The label an operator chose for this provider, or the built-in name. The pending-registration
 * cookie carries only the key, so the row has to be read back to honour the display name here.
 */
private fun providerLabel(
    identityProviderRepository: IdentityProviderRepository?,
    tenant: Tenant?,
    key: ProviderKey,
): String =
    tenant
        ?.let { identityProviderRepository?.findByTenantAndProvider(it.id, key) }
        ?.displayName
        ?.takeIf { it.isNotBlank() }
        ?: EnglishStrings.providerDisplayName(key)

/**
 * Whether [state] is one this instance signed for this tenant and provider, recently.
 *
 * Used only to decide whether an error the provider returned is worth recording — the browser
 * binding is checked where a sign-in is actually completed, never here. The age bound is the same
 * one the completing callback enforces, and it is what keeps a single held state from writing
 * attacker-chosen rows indefinitely and pushing genuine refusals out of the panel's window.
 */
private fun stateWeMinted(
    state: String?,
    encryptionService: EncryptionService,
    provider: ProviderKey,
    slug: String,
): Boolean {
    val payload = state?.let { encryptionService.verifyCookie(it) } ?: return false
    val parsed = SocialState.parse(payload) ?: return false
    if (System.currentTimeMillis() - parsed.timestampMillis > SOCIAL_STATE_MAX_AGE_MS) return false
    return parsed.provider == provider.value && parsed.slug == slug
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
    auditLogPort: AuditLogPort? = null,
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
                call.setSocialStateCookie(slug, provider, encryptionService.signCookie(csrfNonce), secure)
                call.respondRedirect(result.value)
            }
            is SocialLoginResult.Failure -> {
                val enabledProviders =
                    if (tenant != null && identityProviderRepository != null) {
                        identityProviderRepository.findEnabledByTenant(tenant.id).asLoginProviders()
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
                        // Tenant may be unresolved (unknown slug); USERNAME is the safe fallback.
                        loginIdentifierMode =
                            tenant?.securityConfig?.loginIdentifierMode ?: LoginIdentifierMode.USERNAME,
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
                identityProviderRepository.findEnabledByTenant(tenant.id).asLoginProviders()
            } else {
                emptyList()
            }
        // Tenant may be unresolved (unknown slug); USERNAME is the safe, mode-agnostic fallback.
        val loginIdentifierMode = tenant?.securityConfig?.loginIdentifierMode ?: LoginIdentifierMode.USERNAME

        if (socialLoginService == null) {
            return@get call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "social_login_not_configured"))
        }

        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val error = call.request.queryParameters["error"]

        if (!error.isNullOrBlank()) {
            // Only for a state we signed for this tenant and provider. The callback is
            // unauthenticated, so without that check anyone could fill an operator's diagnostics
            // panel with whatever reason they liked. A real IdP echoes the state back with the
            // error (RFC 6749 4.1.2.1); the browser-binding cookie is not required, since a state
            // that expired in the browser is still a failure the operator needs to see.
            if (tenant != null && auditLogPort != null && stateWeMinted(state, encryptionService, provider, slug)) {
                auditLogPort.record(
                    AuditEvent(
                        tenantId = tenant.id,
                        userId = null,
                        clientId = null,
                        eventType = AuditEventType.SOCIAL_LOGIN_FAILED,
                        ipAddress = call.request.origin.remoteAddress,
                        userAgent = call.request.headers["User-Agent"],
                        details =
                            buildMap {
                                put(BrokeredSignInFailure.PROVIDER, provider.value)
                                put(BrokeredSignInFailure.REASON, BrokeredSignInFailure.IDP_RETURNED_ERROR)
                                BrokeredSignInFailure.idpErrorCode(error)?.let {
                                    put(BrokeredSignInFailure.IDP_ERROR_CODE, it)
                                }
                            },
                    ),
                )
            }
            call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.loginPage(
                    tenantSlug = slug,
                    ctx = ctx.viewContext,
                    error =
                        "Login with ${providerLabel(identityProviderRepository, tenant, provider)} " +
                            "was cancelled or failed.",
                    enabledProviders = enabledProviders,
                    passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                    passkeysEnabled = tenant?.passkeysEnabled == true,
                    loginIdentifierMode = loginIdentifierMode,
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
                    loginIdentifierMode = loginIdentifierMode,
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
                    loginIdentifierMode = loginIdentifierMode,
                ),
            )
            return@get
        }

        val secure = baseUrl.startsWith("https://", ignoreCase = true)

        // The signature says we minted this state; only the cookie says this browser began the
        // flow. Without it a state minted by an attacker, replayed at the victim, signs the victim
        // in as the attacker — every other guard on this path passes on an attacker-minted state.
        val boundNonce =
            call.request.cookies[socialStateCookieName(provider, slug, secure)]
                ?.let { encryptionService.verifyCookie(it) }
        if (boundNonce == null || !constantTimeEquals(boundNonce, socialState.csrfNonce)) {
            // Deliberately left in place: clearing here lets anyone holding a signed state cancel
            // an in-flight login of the browser they can reach. The cookie is age-bounded anyway.
            call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.loginPage(
                    tenantSlug = slug,
                    ctx = ctx.viewContext,
                    error = "This sign-in did not start in this browser. Please try signing in again.",
                    enabledProviders = enabledProviders,
                    passwordLoginEnabled = tenant?.securityConfig?.passwordLoginEnabled != false,
                    passkeysEnabled = tenant?.passkeysEnabled == true,
                    loginIdentifierMode = loginIdentifierMode,
                ),
            )
            return@get
        }
        call.clearSocialStateCookie(slug, provider, secure)

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

        val ipAddress = call.request.origin.remoteAddress
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
                    originatingClientId = restoredParams.clientId,
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
                        loginIdentifierMode = loginIdentifierMode,
                    ),
                )
            }
            is SocialLoginResult.NeedsRegistration -> {
                val pending = result.data
                val refusal = pending.jitRefusal
                // The gate governs auto-creation, not all account creation: `jitAllowedDomains`
                // says who is created automatically, `registrationEnabled` says who may sign up at
                // all. They are separate doors with separate switches, so a refusal here must not
                // close one the operator configured open — switching on a convenience feature
                // would otherwise silently narrow a setting chosen independently of it. Where
                // sign-up is open the person falls through to the completion page, which is the
                // truthful outcome: an account is still available, just not an automatic one.
                // Only where sign-up is closed is "not permitted here" the whole truth, and
                // offering a sign-up form to someone just told they are refused is incoherent.
                // The refusal is recorded by the gate either way, below this decision.
                val refusalReference = pending.jitReference
                if (refusal != null && refusalReference != null && tenant != null && !tenant.registrationEnabled) {
                    call.respondHtml(
                        HttpStatusCode.Forbidden,
                        AuthView.jitRefusedPage(
                            tenantSlug = slug,
                            ctx = ctx.viewContext,
                            providerName = providerLabel(identityProviderRepository, tenant, provider),
                            refusal = refusal,
                            // The gate's own reference, not a second computation of it.
                            reference = refusalReference,
                        ),
                    )
                    return@get
                }
                val pendingNonce =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                call.setSocialPendingCookies(
                    slug = slug,
                    signedPending =
                        encryptionService.signCookie(
                            buildSocialPendingPayload(pending, slug, oauthParamsRaw, pendingNonce),
                        ),
                    signedNonce = encryptionService.signCookie(pendingNonce),
                    secure = secure,
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
                                    loginIdentifierMode = activeTenant.securityConfig.loginIdentifierMode,
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

        val secure = baseUrl.startsWith("https://", ignoreCase = true)
        val pending = call.readSocialPending(slug, secure, encryptionService)

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
                providerName = providerLabel(identityProviderRepository, tenant, pending.provider),
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

        val secure = baseUrl.startsWith("https://", ignoreCase = true)
        val pending = call.readSocialPending(slug, secure, encryptionService)

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
        val ipAddress = call.request.origin.remoteAddress
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
                        providerName = providerLabel(identityProviderRepository, tenant, pending.provider),
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
                        providerName = providerLabel(identityProviderRepository, tenant, pending.provider),
                        email = pending.email,
                        error = "An unexpected error occurred. Please try again.",
                    ),
                )
            }
            is SocialLoginResult.Success -> {
                call.clearSocialPendingCookies(slug, secure)
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
                                    providerName = providerLabel(identityProviderRepository, tenant, pending.provider),
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
