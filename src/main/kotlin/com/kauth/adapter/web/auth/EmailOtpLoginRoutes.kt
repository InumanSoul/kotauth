package com.kauth.adapter.web.auth

import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.EmailOtpService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.OtpError
import com.kauth.domain.service.OtpSendResult
import com.kauth.domain.service.OtpVerifyResult
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.delay

private const val CONSTANT_TIME_TARGET_MS = 200L
private const val PER_IP_RATE_PREFIX = "otp_login_ip"

internal fun Route.emailOtpLoginRoutes(
    emailOtpService: EmailOtpService,
    oauthService: OAuthService,
    perIpLimiter: RateLimiterPort,
    encryptionService: EncryptionService,
    ssoTtlSeconds: Long,
    secure: Boolean,
) {
    get("/email-otp") {
        val ctx = call.attributes[AuthTenantAttr]
        if (ctx.tenant?.securityConfig?.emailOtpLoginEnabled != true) {
            return@get call.respondRedirect("/t/${ctx.slug}/account/login")
        }
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.emailOtpEnterEmailPage(
                tenantSlug = ctx.slug,
                ctx = ctx.viewContext,
            ),
        )
    }

    post("/email-otp/send") {
        val ctx = call.attributes[AuthTenantAttr]
        if (ctx.tenant?.securityConfig?.emailOtpLoginEnabled != true) {
            return@post call.respondRedirect("/t/${ctx.slug}/account/login")
        }

        val params = call.receiveParameters()
        val email = params["email"]?.trim()?.lowercase().orEmpty()
        val isResend = params["resend"] == "true"
        val ipAddress = call.request.local.remoteAddress

        if (email.isBlank() || "@" !in email) {
            return@post call.respondHtml(
                HttpStatusCode.OK,
                AuthView.emailOtpEnterEmailPage(
                    tenantSlug = ctx.slug,
                    ctx = ctx.viewContext,
                    error = "Enter a valid email address.",
                ),
            )
        }

        if (!perIpLimiter.isAllowed("$PER_IP_RATE_PREFIX:$ipAddress")) {
            return@post call.respondRedirect("/t/${ctx.slug}/email-otp/verify${if (isResend) "?resent=true" else ""}")
        }

        val oauthParams = call.getAuthContext(encryptionService) ?: AuthView.OAuthParams()

        val start = System.nanoTime()
        val result =
            emailOtpService.sendOtp(
                tenantSlug = ctx.slug,
                email = email,
                originatingClientId = oauthParams.clientId,
                ipAddress = ipAddress,
            )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val remaining = CONSTANT_TIME_TARGET_MS - elapsedMs
        if (remaining > 0) delay(remaining)

        when (result) {
            is OtpSendResult.Success -> {
                call.setAuthContextCookie(
                    params = oauthParams,
                    slug = ctx.slug,
                    encryptionService = encryptionService,
                    secure = secure,
                    otpChallengeId = result.challengeId,
                    otpEmail = email,
                )
                call.respondRedirect("/t/${ctx.slug}/email-otp/verify${if (isResend) "?resent=true" else ""}")
            }
            is OtpSendResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.OK,
                    AuthView.emailOtpEnterEmailPage(
                        tenantSlug = ctx.slug,
                        ctx = ctx.viewContext,
                        error = "Could not send the code. Try again in a moment.",
                    ),
                )
        }
    }

    get("/email-otp/verify") {
        val ctx = call.attributes[AuthTenantAttr]
        if (ctx.tenant?.securityConfig?.emailOtpLoginEnabled != true) {
            return@get call.respondRedirect("/t/${ctx.slug}/account/login")
        }
        val otp = call.getOtpFlowContext(encryptionService)
        if (otp == null) {
            return@get call.respondRedirect("/t/${ctx.slug}/email-otp")
        }
        val resent = call.request.queryParameters["resent"] == "true"
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.emailOtpEnterCodePage(
                tenantSlug = ctx.slug,
                ctx = ctx.viewContext,
                email = otp.email,
                resent = resent,
            ),
        )
    }

    post("/email-otp/verify") {
        val ctx = call.attributes[AuthTenantAttr]
        if (ctx.tenant?.securityConfig?.emailOtpLoginEnabled != true) {
            return@post call.respondRedirect("/t/${ctx.slug}/account/login")
        }

        val otp = call.getOtpFlowContext(encryptionService)
        if (otp == null) {
            return@post call.respondRedirect("/t/${ctx.slug}/email-otp")
        }

        val params = call.receiveParameters()
        val code = params["code"]?.trim().orEmpty()
        val ipAddress = call.request.local.remoteAddress

        if (!code.matches(Regex("\\d{6}"))) {
            return@post call.respondHtml(
                HttpStatusCode.OK,
                AuthView.emailOtpEnterCodePage(
                    tenantSlug = ctx.slug,
                    ctx = ctx.viewContext,
                    email = otp.email,
                    error = "Enter the 6-digit code from your email.",
                ),
            )
        }

        when (
            val result =
                emailOtpService.verifyOtp(
                    tenantSlug = ctx.slug,
                    challengeId = otp.challengeId,
                    code = code,
                    ipAddress = ipAddress,
                )
        ) {
            is OtpVerifyResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.OK,
                    AuthView.emailOtpEnterCodePage(
                        tenantSlug = ctx.slug,
                        ctx = ctx.viewContext,
                        email = otp.email,
                        error = otp.errorMessageFor(result.error),
                    ),
                )
            is OtpVerifyResult.Success -> {
                val tenant = ctx.tenant
                val oauthParams = call.getAuthContext(encryptionService) ?: AuthView.OAuthParams()
                call.completeAuthorizationCodeFlow(
                    slug = ctx.slug,
                    userId = result.userId,
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
                            AuthView.emailOtpErrorPage(
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

private fun com.kauth.adapter.web.auth.OtpFlowContext.errorMessageFor(error: OtpError): String =
    when (error) {
        OtpError.InvalidOtp -> "Wrong code. Try again or request a new one."
        OtpError.ChallengeExpired -> "That code has expired. Request a new one."
        OtpError.TooManyAttempts -> "Too many attempts. Try again later."
        OtpError.InvalidClient -> "Sign-in is misconfigured. Contact support."
        OtpError.TenantNotFound -> "Workspace not found."
    }
