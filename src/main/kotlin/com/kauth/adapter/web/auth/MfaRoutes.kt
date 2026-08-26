package com.kauth.adapter.web.auth

import com.kauth.domain.model.UserId
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.MfaError
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

// The pending MFA challenge: `userId|slug|timestampMillis`.
private const val MFA_PENDING_FIELD_COUNT = 3
private const val MFA_PENDING_SLUG = 1

internal fun Route.mfaRoutes(
    oauthService: OAuthService,
    mfaService: MfaService?,
    encryptionService: EncryptionService,
    mfaRateLimiter: RateLimiterPort,
    ssoTtlSeconds: Long,
    secure: Boolean,
) {
    get("/mfa-challenge") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug

        val rawPendingGet = call.request.cookies["KOTAUTH_MFA_PENDING"]
        val pendingGet = rawPendingGet?.takeIf { it.isNotBlank() }?.let { encryptionService.verifyCookie(it) }
        val partsGet = pendingGet?.split("|")
        // The slug travels in the payload; comparing it is what makes the cookie this tenant's.
        // A signature proves only that we minted it, not that it belongs here. The field count is
        // pinned exactly as the POST pins it: two readers of one format must not disagree on it.
        if (partsGet == null ||
            partsGet.size != MFA_PENDING_FIELD_COUNT ||
            partsGet[MFA_PENDING_SLUG] != slug
        ) {
            return@get call.respondRedirect("/t/$slug/authorize")
        }

        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.mfaChallengePage(slug, ctx.viewContext),
        )
    }

    post("/mfa-challenge") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val params = call.receiveParameters()
        val code = params["code"]?.trim() ?: ""
        val ipAddress = call.request.origin.remoteAddress

        val rateLimitKey = "mfa:$ipAddress:$slug"
        if (!mfaRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.mfaChallengePage(
                    slug,
                    ctx.viewContext,
                    error = "Too many attempts. Please wait a few minutes and try again.",
                ),
            )
        }

        // OAuth context is read from the signed auth context cookie, not from form fields.
        val oauthParams = call.getAuthContext(encryptionService) ?: AuthView.OAuthParams()

        val rawCookie = call.request.cookies["KOTAUTH_MFA_PENDING"]
        if (rawCookie.isNullOrBlank()) {
            return@post call.respondRedirect("/t/$slug/authorize")
        }
        val pending = encryptionService.verifyCookie(rawCookie)
        if (pending == null) {
            return@post call.respondRedirect("/t/$slug/authorize")
        }
        val parts = pending.split("|")
        if (parts.size != MFA_PENDING_FIELD_COUNT || parts[MFA_PENDING_SLUG] != slug) {
            return@post call.respondRedirect("/t/$slug/authorize")
        }
        val userId = parts[0].toIntOrNull() ?: return@post call.respondRedirect("/t/$slug/authorize")
        val timestamp = parts[2].toLongOrNull() ?: return@post call.respondRedirect("/t/$slug/authorize")

        if (System.currentTimeMillis() - timestamp > 300_000) {
            return@post call.respondHtml(
                HttpStatusCode.Unauthorized,
                AuthView.mfaChallengePage(
                    slug,
                    ctx.viewContext,
                    error = "MFA challenge expired. Please log in again.",
                ),
            )
        }

        if (mfaService == null) {
            return@post call.respondRedirect("/t/$slug/authorize")
        }

        val mfaResult =
            if (code.length == 6 && code.all { it.isDigit() }) {
                mfaService.verifyTotp(UserId(userId), code)
            } else {
                mfaService.verifyRecoveryCode(UserId(userId), code)
            }

        when (mfaResult) {
            is MfaResult.Failure -> {
                val message =
                    if (mfaResult.error is MfaError.TotpLocked) {
                        "Too many failed attempts. MFA is temporarily locked — try again later or use a recovery code."
                    } else {
                        "Invalid code. Please try again."
                    }
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.mfaChallengePage(slug, ctx.viewContext, error = message),
                )
            }
            is MfaResult.Success -> {
                call.response.cookies.append(
                    name = "KOTAUTH_MFA_PENDING",
                    value = "",
                    maxAge = 0L,
                    path = "/t/$slug",
                    httpOnly = true,
                    secure = secure,
                )

                if (oauthParams.isOAuthFlow) {
                    val tenant =
                        ctx.tenant ?: return@post call.respond(HttpStatusCode.NotFound)
                    call.completeAuthorizationCodeFlow(
                        slug = slug,
                        userId = UserId(userId),
                        tenantId = tenant.id,
                        oauthParams = oauthParams,
                        ipAddress = ipAddress,
                        // Keycloak convention: auth_time tracks the moment MFA was completed,
                        // not the original first-factor (password) check.
                        authTime = java.time.Instant.now(),
                        mfaCompleted = true,
                        ssoTtlSeconds = ssoTtlSeconds,
                        secure = secure,
                        oauthService = oauthService,
                        encryptionService = encryptionService,
                        renderError = { message ->
                            call.respondHtml(
                                HttpStatusCode.BadRequest,
                                AuthView.mfaChallengePage(
                                    slug,
                                    ctx.viewContext,
                                    error = message,
                                ),
                            )
                        },
                    )
                } else {
                    call.respond(
                        mapOf(
                            "message" to "MFA verification successful",
                            "user_id" to userId,
                        ),
                    )
                }
            }
        }
    }
}
