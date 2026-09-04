package com.kauth.adapter.web.auth

import com.kauth.adapter.web.admin.resolvedBaseUrl
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SelfServiceResult
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Passwordless sign-in via emailed one-time link.
 *
 * Flow (same-device):
 *   1. User on `/t/{slug}/authorize` → clicks "Sign in with email" on the login page
 *   2. `POST /magic-link/send` — emails the link, redirects to `?sent=true`
 *   3. User opens email (in same browser) → clicks link
 *   4. `GET /magic-link/consume?token=…` — token verified, OAuth context from
 *      the cookie used to complete the authorization_code flow
 *
 * Cross-device / expired-cookie flow:
 *   Consumption requires the `KOTAUTH_AUTH_CONTEXT` cookie set by the original
 *   `/authorize` request. If absent (different browser, cookie expired after
 *   5 minutes), the user sees a friendly error asking them to open the link in
 *   the same browser. The OAuth-context check runs **before** `consumeMagicLink`,
 *   so the token is preserved and a same-device retry still works. `consumeMagicLink`
 *   is given the tenant from the URL for the same reason: a link tapped at the wrong
 *   tenant is refused before the token is marked used, not after.
 *
 * Gated on `tenant.securityConfig.magicLinkEnabled` — off by default per tenant.
 */
internal fun Route.magicLinkRoutes(
    credentialFlowService: CredentialFlowService,
    rateLimiter: RateLimiterPort,
    encryptionService: EncryptionService,
    oauthService: OAuthService,
    ssoTtlSeconds: Long,
    secure: Boolean,
) {
    get("/magic-link") {
        val ctx = call.attributes[AuthTenantAttr]
        if (ctx.tenant?.securityConfig?.magicLinkEnabled != true) {
            return@get call.respondRedirect("/t/${ctx.slug}/account/login")
        }
        val sent = call.request.queryParameters["sent"] == "true"
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.magicLinkPage(
                tenantSlug = ctx.slug,
                ctx = ctx.viewContext,
                sent = sent,
            ),
        )
    }

    post("/magic-link/send") {
        val ctx = call.attributes[AuthTenantAttr]
        if (ctx.tenant?.securityConfig?.magicLinkEnabled != true) {
            // Even when disabled, redirect to ?sent=true — don't reveal feature state
            return@post call.respondRedirect("/t/${ctx.slug}/magic-link?sent=true")
        }

        val ipAddress = call.request.origin.remoteAddress
        val rateLimitKey = "magic-link:$ipAddress:${ctx.slug}"
        // Even on rate-limit hit, respond with the success state — no timing oracle
        if (!rateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondRedirect("/t/${ctx.slug}/magic-link?sent=true")
        }

        val params = call.receiveParameters()
        val email = params["email"]?.trim().orEmpty()
        val baseUrl = call.resolvedBaseUrl()

        // Service never fails externally — user enumeration is handled inside
        credentialFlowService.initiateMagicLink(
            email = email,
            tenantSlug = ctx.slug,
            baseUrl = baseUrl,
            ipAddress = ipAddress,
        )

        // Refresh the OAuth context cookie's timestamp so the user gets a full
        // fresh 5-minute window to click the link from their inbox.
        val oauthParams = call.getAuthContext(encryptionService)
        if (oauthParams != null) {
            call.setAuthContextCookie(oauthParams, ctx.slug, encryptionService)
        }

        call.respondRedirect("/t/${ctx.slug}/magic-link?sent=true")
    }

    get("/magic-link/consume") {
        val ctx = call.attributes[AuthTenantAttr]
        if (ctx.tenant?.securityConfig?.magicLinkEnabled != true) {
            return@get call.respondRedirect("/t/${ctx.slug}/account/login")
        }

        val token = call.request.queryParameters["token"] ?: ""
        if (token.isBlank()) {
            return@get call.respondRedirect("/t/${ctx.slug}/magic-link")
        }

        // Check OAuth context BEFORE consuming the token. If absent, the user
        // is on the wrong device/browser; bailing out here keeps the token
        // alive so a same-device retry still works. Consuming first would
        // burn the token on every cross-device tap and turn the friendly
        // error into a dead end.
        val oauthParams = call.getAuthContext(encryptionService)
        if (oauthParams == null) {
            call.respondHtml(
                HttpStatusCode.Unauthorized,
                AuthView.magicLinkErrorPage(
                    tenantSlug = ctx.slug,
                    ctx = ctx.viewContext,
                    error =
                        "To finish signing in, open this link in the same browser where you " +
                            "requested it, or go back to the sign-in page and request a new link.",
                ),
            )
            return@get
        }

        // ctx.tenant is non-null past the magicLinkEnabled gate at the top of the route.
        val tenant = ctx.tenant
        when (val result = credentialFlowService.consumeMagicLink(token, tenant.id)) {
            is SelfServiceResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.magicLinkErrorPage(
                        tenantSlug = ctx.slug,
                        ctx = ctx.viewContext,
                        error = result.error.message,
                    ),
                )
            }
            is SelfServiceResult.Success -> {
                val user = result.value
                val ipAddress = call.request.origin.remoteAddress
                call.completeAuthorizationCodeFlow(
                    slug = ctx.slug,
                    userId = user.id!!,
                    tenantId = tenant.id,
                    oauthParams = oauthParams,
                    ipAddress = ipAddress,
                    authTime = java.time.Instant.now(),
                    mfaCompleted = false,
                    ssoTtlSeconds = ssoTtlSeconds,
                    secure = secure,
                    oauthService = oauthService,
                    encryptionService = encryptionService,
                    renderError = { message ->
                        call.respondHtml(
                            HttpStatusCode.BadRequest,
                            AuthView.magicLinkErrorPage(
                                tenantSlug = ctx.slug,
                                ctx = ctx.viewContext,
                                error = message,
                            ),
                        )
                    },
                )
            }
        }
    }
}
