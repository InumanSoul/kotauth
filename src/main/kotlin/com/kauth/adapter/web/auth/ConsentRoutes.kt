package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.service.ConsentRequest
import com.kauth.domain.service.ConsentService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.util.SecureTokens
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant

private const val CONSENT_PENDING_COOKIE = "KOTAUTH_CONSENT_PENDING"
private const val CONSENT_PENDING_TTL_SECONDS = 300L

// `userId|slug|issuedAtMillis|authTimeEpochSeconds|amr|nonce`, amr comma-separated.
private const val CONSENT_FIELD_COUNT = 6
private const val CONSENT_USER = 0
private const val CONSENT_SLUG = 1
private const val CONSENT_ISSUED_AT = 2
private const val CONSENT_AUTH_TIME = 3
private const val CONSENT_AMR = 4
private const val CONSENT_NONCE = 5

private data class PendingConsent(
    val userId: UserId,
    val authTime: Instant,
    val amr: List<String>,
    val nonce: String,
)

/**
 * Sends a signed-in user to the consent screen instead of back to the application. The sign-in
 * itself is finished; what it proved travels in a signed cookie so that Allow can issue the code.
 */
internal fun ApplicationCall.startConsent(
    slug: String,
    userId: UserId,
    oauthParams: AuthView.OAuthParams,
    authTime: Instant,
    amr: List<String>,
    encryptionService: EncryptionService,
    secure: Boolean,
): String {
    val payload =
        listOf(
            userId.value.toString(),
            slug,
            System.currentTimeMillis().toString(),
            authTime.epochSecond.toString(),
            amr.joinToString(","),
            SecureTokens.randomBase64Url(16),
        ).joinToString("|")
    response.cookies.append(
        name = CONSENT_PENDING_COOKIE,
        value = encryptionService.signCookie(payload),
        maxAge = CONSENT_PENDING_TTL_SECONDS,
        httpOnly = true,
        secure = secure,
        path = "/t/$slug",
    )
    setAuthContextCookie(oauthParams, slug, encryptionService, secure)
    return "/t/$slug/consent"
}

private fun ApplicationCall.pendingConsent(
    slug: String,
    encryptionService: EncryptionService,
): PendingConsent? {
    val raw = request.cookies[CONSENT_PENDING_COOKIE]?.takeIf { it.isNotBlank() } ?: return null
    val parts = encryptionService.verifyCookie(raw)?.split("|") ?: return null
    // The slug in the payload is what makes the cookie this workspace's; the signature only says we minted it.
    if (parts.size != CONSENT_FIELD_COUNT || parts[CONSENT_SLUG] != slug) return null
    val issuedAt = parts[CONSENT_ISSUED_AT].toLongOrNull() ?: return null
    if (System.currentTimeMillis() - issuedAt > CONSENT_PENDING_TTL_SECONDS * 1000) return null
    return PendingConsent(
        userId = UserId(parts[CONSENT_USER].toIntOrNull() ?: return null),
        authTime = Instant.ofEpochSecond(parts[CONSENT_AUTH_TIME].toLongOrNull() ?: return null),
        amr = parts[CONSENT_AMR].split(",").filter { it.isNotBlank() },
        nonce = parts[CONSENT_NONCE],
    )
}

private fun ApplicationCall.clearPendingConsent(slug: String) {
    response.cookies.append(name = CONSENT_PENDING_COOKIE, value = "", maxAge = 0L, path = "/t/$slug")
}

/** A consent request that is still open: the pending sign-in, the OAuth request and what it asks for. */
private class OpenConsent(
    val pending: PendingConsent,
    val oauthParams: AuthView.OAuthParams,
    val redirectUri: String,
    val scope: String,
    val request: ConsentRequest,
)

private fun ApplicationCall.openConsent(
    slug: String,
    tenantId: TenantId,
    consentService: ConsentService,
    encryptionService: EncryptionService,
): OpenConsent? {
    val pending = pendingConsent(slug, encryptionService) ?: return null
    val oauthParams = getAuthContext(encryptionService) ?: return null
    val clientId = oauthParams.clientId ?: return null
    val redirectUri = oauthParams.redirectUri ?: return null
    val scope = oauthParams.scope ?: "openid"
    val request = consentService.request(tenantId, pending.userId, clientId, scope) ?: return null
    return OpenConsent(pending, oauthParams, redirectUri, scope, request)
}

private fun deniedRedirect(open: OpenConsent): String =
    buildString {
        append(open.redirectUri)
        append(if ('?' in open.redirectUri) '&' else '?')
        append("error=access_denied")
        append("&error_description=").append(encodeParam("The user denied the request"))
        open.oauthParams.state
            ?.takeIf { it.isNotBlank() }
            ?.let { append("&state=").append(encodeParam(it)) }
    }

internal fun Route.consentRoutes(
    consentService: ConsentService,
    oauthService: OAuthService,
    encryptionService: EncryptionService,
    ssoTtlSeconds: Long,
    secure: Boolean,
) {
    get("/consent") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant ?: return@get call.respondRedirect("/t/$slug/authorize")
        val open =
            call.openConsent(slug, tenant.id, consentService, encryptionService)
                ?: return@get call.respondHtml(HttpStatusCode.BadRequest, AuthView.consentExpiredPage(ctx.viewContext))
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.consentPage(slug, ctx.viewContext, open.request, open.pending.nonce),
        )
    }

    post("/consent") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant ?: return@post call.respondRedirect("/t/$slug/authorize")
        val form = call.receiveParameters()
        val open =
            call
                .openConsent(slug, tenant.id, consentService, encryptionService)
                ?.takeIf { form["nonce"] == it.pending.nonce }
                // The redirect URI came through a signed cookie, but a denial redirects without
                // issuing a code, so it is checked again here rather than trusted.
                ?.takeIf {
                    oauthService.checkAuthorizeTarget(slug, it.request.application.clientId, it.redirectUri) ==
                        OAuthService.AuthorizeTarget.TRUSTED
                }
                ?: return@post call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.consentExpiredPage(ctx.viewContext),
                )
        val ipAddress = call.request.origin.remoteAddress
        val userId = open.pending.userId
        call.clearPendingConsent(slug)

        if (form["decision"] != "allow") {
            consentService.deny(tenant.id, userId, open.request.application, open.scope, ipAddress)
            call.clearAuthContextCookie(slug)
            return@post call.respondRedirect(deniedRedirect(open))
        }

        consentService.grant(tenant.id, userId, open.request.application, open.scope, ipAddress)
        val url =
            call.buildAuthorizationCodeRedirectUrl(
                slug = slug,
                userId = userId,
                tenantId = tenant.id,
                oauthParams = open.oauthParams.copy(forceConsent = false),
                ipAddress = ipAddress,
                authTime = open.pending.authTime,
                amr = open.pending.amr,
                ssoTtlSeconds = ssoTtlSeconds,
                secure = secure,
                oauthService = oauthService,
                encryptionService = encryptionService,
                renderError = { message ->
                    call.respondHtml(HttpStatusCode.BadRequest, AuthView.consentExpiredPage(ctx.viewContext, message))
                },
            ) ?: return@post
        call.respondRedirect(url)
    }
}
