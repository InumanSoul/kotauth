package com.kauth.adapter.web.auth

import com.kauth.domain.model.UserId
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

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
        if (rawPendingGet.isNullOrBlank() || encryptionService.verifyCookie(rawPendingGet) == null) {
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
        val ipAddress = call.request.local.remoteAddress

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
        if (parts.size != 3) {
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
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.mfaChallengePage(
                        slug,
                        ctx.viewContext,
                        error = "Invalid code. Please try again.",
                    ),
                )
            }
            is MfaResult.Success -> {
                call.response.cookies.append(
                    name = "KOTAUTH_MFA_PENDING",
                    value = "",
                    maxAge = 0L,
                    path = "/t/$slug",
                    httpOnly = true,
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
