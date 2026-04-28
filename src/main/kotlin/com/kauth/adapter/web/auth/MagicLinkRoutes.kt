package com.kauth.adapter.web.auth

import com.kauth.adapter.web.admin.resolvedBaseUrl
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SelfServiceResult
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
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
 * Cross-device / expired-cookie flow (v1.7.0):
 *   Consumption requires the `KOTAUTH_AUTH_CONTEXT` cookie set by the original
 *   `/authorize` request. If absent (different browser, cookie expired after
 *   5 minutes), the user sees a friendly error asking them to open the link in
 *   the same browser or request a new one.
 *
 * Gated on `tenant.securityConfig.magicLinkEnabled` — off by default per tenant.
 */
internal fun Route.magicLinkRoutes(
    selfServiceService: UserSelfServiceService,
    rateLimiter: RateLimiterPort,
    encryptionService: EncryptionService,
    oauthService: OAuthService,
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
                theme = ctx.theme,
                workspaceName = ctx.workspaceName,
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

        val ipAddress = call.request.local.remoteAddress
        val rateLimitKey = "magic-link:$ipAddress:${ctx.slug}"
        // Even on rate-limit hit, respond with the success state — no timing oracle
        if (!rateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondRedirect("/t/${ctx.slug}/magic-link?sent=true")
        }

        val params = call.receiveParameters()
        val email = params["email"]?.trim().orEmpty()
        val baseUrl = call.resolvedBaseUrl()

        // Service never fails externally — user enumeration is handled inside
        selfServiceService.initiateMagicLink(
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

        when (val result = selfServiceService.consumeMagicLink(token)) {
            is SelfServiceResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.magicLinkErrorPage(
                        tenantSlug = ctx.slug,
                        theme = ctx.theme,
                        workspaceName = ctx.workspaceName,
                        error = result.error.message,
                    ),
                )
            }
            is SelfServiceResult.Success -> {
                val user = result.value
                val oauthParams = call.getAuthContext(encryptionService)
                if (oauthParams == null) {
                    // No OAuth context → either cross-device or expired cookie.
                    // v1.7.0: direct the user to the login page to start a fresh
                    // session. Their account is verified; they just need to
                    // re-initiate the flow in this browser.
                    call.respondHtml(
                        HttpStatusCode.Unauthorized,
                        AuthView.magicLinkErrorPage(
                            tenantSlug = ctx.slug,
                            theme = ctx.theme,
                            workspaceName = ctx.workspaceName,
                            error =
                                "To finish signing in, open this link in the same browser where you " +
                                    "requested it — or go back to the sign-in page and request a new link.",
                        ),
                    )
                    return@get
                }

                val ipAddress = call.request.local.remoteAddress
                call.completeAuthorizationCodeFlow(
                    slug = ctx.slug,
                    userId = user.id!!,
                    oauthParams = oauthParams,
                    ipAddress = ipAddress,
                    oauthService = oauthService,
                    renderError = { message ->
                        call.respondHtml(
                            HttpStatusCode.BadRequest,
                            AuthView.magicLinkErrorPage(
                                tenantSlug = ctx.slug,
                                theme = ctx.theme,
                                workspaceName = ctx.workspaceName,
                                error = message,
                            ),
                        )
                    },
                )
            }
        }
    }
}
