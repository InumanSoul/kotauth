package com.kauth.adapter.web.auth

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.UserId
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.userAgent
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant

internal const val MFA_ENROLL_COOKIE = "KOTAUTH_MFA_ENROLL"

// Long enough to install an authenticator app; each step must still land within the auth context's lifetime.
private const val MFA_ENROLL_TTL_SECONDS = 900L
private const val MFA_ENROLL_FIELD_COUNT = 5

/**
 * A sign-in whose first factor passed but whose workspace requires a second factor the user has
 * not set up: `userId|slug|startedAtMillis|enrollmentId|verified`. [enrollmentId] is 0 until the
 * setup starts, which pins the flow to the enrollment it created.
 */
private data class PendingEnrollment(
    val userId: UserId,
    val slug: String,
    val startedAtMillis: Long,
    val enrollmentId: Int,
    val verified: Boolean,
) {
    fun encode(): String = "${userId.value}|$slug|$startedAtMillis|$enrollmentId|${if (verified) 1 else 0}"
}

internal fun ApplicationCall.startMfaEnrollment(
    slug: String,
    userId: UserId,
    oauthParams: AuthView.OAuthParams,
    encryptionService: EncryptionService,
    secure: Boolean,
): String {
    writePendingEnrollment(
        PendingEnrollment(userId, slug, System.currentTimeMillis(), enrollmentId = 0, verified = false),
        encryptionService,
        secure,
    )
    setAuthContextCookie(oauthParams, slug, encryptionService, secure)
    return "/t/$slug/mfa-enroll"
}

private fun ApplicationCall.writePendingEnrollment(
    pending: PendingEnrollment,
    encryptionService: EncryptionService,
    secure: Boolean,
) {
    val remaining = MFA_ENROLL_TTL_SECONDS - (System.currentTimeMillis() - pending.startedAtMillis) / 1000
    response.cookies.append(
        name = MFA_ENROLL_COOKIE,
        value = encryptionService.signCookie(pending.encode()),
        maxAge = remaining.coerceAtLeast(0),
        httpOnly = true,
        secure = secure,
        path = "/t/${pending.slug}",
    )
}

private fun ApplicationCall.clearPendingEnrollment(
    slug: String,
    secure: Boolean,
) {
    response.cookies.append(
        name = MFA_ENROLL_COOKIE,
        value = "",
        maxAge = 0L,
        httpOnly = true,
        secure = secure,
        path = "/t/$slug",
    )
}

/** The pending enrollment for this workspace, or null when missing, tampered with, another workspace's or expired. */
private fun ApplicationCall.readPendingEnrollment(
    slug: String,
    encryptionService: EncryptionService,
): PendingEnrollment? {
    val raw = request.cookies[MFA_ENROLL_COOKIE]?.takeIf { it.isNotBlank() } ?: return null
    val parts = encryptionService.verifyCookie(raw)?.split("|") ?: return null
    if (parts.size != MFA_ENROLL_FIELD_COUNT || parts[1] != slug) return null
    val pending =
        PendingEnrollment(
            userId = UserId(parts[0].toIntOrNull() ?: return null),
            slug = slug,
            startedAtMillis = parts[2].toLongOrNull() ?: return null,
            enrollmentId = parts[3].toIntOrNull() ?: return null,
            verified = parts[4] == "1",
        )
    val age = System.currentTimeMillis() - pending.startedAtMillis
    return pending.takeIf { age in 0..MFA_ENROLL_TTL_SECONDS * 1000 }
}

/**
 * Sets up the second factor inside the sign-in when the workspace requires one the user lacks,
 * as Auth0's enrollment prompt and Clerk's `setup-mfa` task do: scan the QR code, prove the app
 * works with a code, save the recovery codes, then continue to the application. Proving the new
 * factor counts as the second factor of this sign-in.
 */
internal fun Route.mfaEnrollmentRoutes(
    oauthService: OAuthService,
    mfaService: MfaService?,
    encryptionService: EncryptionService,
    mfaRateLimiter: RateLimiterPort,
    ssoTtlSeconds: Long,
    secure: Boolean,
) {
    // Without MFA the gate never asks for a setup.
    if (mfaService == null) return
    val steps = MfaEnrollmentSteps(oauthService, mfaService, encryptionService, mfaRateLimiter, ssoTtlSeconds, secure)
    get("/mfa-enroll") { steps.step(call)?.let { steps.show(it) } }
    post("/mfa-enroll/start") { steps.step(call)?.let { steps.start(it) } }
    post("/mfa-enroll/verify") { steps.step(call)?.let { steps.verify(it) } }
    post("/mfa-enroll/complete") { steps.step(call)?.let { steps.complete(it) } }
}

/** One request of the setup, with the state every step needs. */
private class EnrollmentStep(
    val call: ApplicationCall,
    val ctx: AuthTenantContext,
    val tenant: Tenant,
    val pending: PendingEnrollment,
    val oauthParams: AuthView.OAuthParams,
) {
    val slug get() = ctx.slug
}

private class MfaEnrollmentSteps(
    private val oauthService: OAuthService,
    private val mfaService: MfaService,
    private val encryptionService: EncryptionService,
    private val mfaRateLimiter: RateLimiterPort,
    private val ssoTtlSeconds: Long,
    private val secure: Boolean,
) {
    /**
     * Reads the pending enrollment and the OAuth request it belongs to, and writes the auth
     * context back with a fresh timestamp so a slow setup does not outlive the request. Sends the
     * browser back to sign-in and returns null when either is missing.
     */
    suspend fun step(call: ApplicationCall): EnrollmentStep? {
        val ctx = call.attributes[AuthTenantAttr]
        val pending = call.readPendingEnrollment(ctx.slug, encryptionService)
        val oauthParams = call.getAuthContext(encryptionService)
        val tenant = ctx.tenant
        if (pending == null || oauthParams == null || tenant == null) {
            call.clearPendingEnrollment(ctx.slug, secure)
            call.respondRedirect("/t/${ctx.slug}/account/login")
            return null
        }
        call.setAuthContextCookie(oauthParams, ctx.slug, encryptionService, secure)
        return EnrollmentStep(call, ctx, tenant, pending, oauthParams)
    }

    suspend fun show(step: EnrollmentStep) {
        val page =
            if (step.pending.verified) {
                AuthView.mfaEnrollDonePage(step.slug, step.ctx.viewContext)
            } else {
                AuthView.mfaEnrollIntroPage(step.slug, step.ctx.viewContext)
            }
        step.call.respondHtml(HttpStatusCode.OK, page)
    }

    suspend fun start(step: EnrollmentStep) {
        val call = step.call
        if (step.pending.verified) return call.respondRedirect("/t/${step.slug}/mfa-enroll")
        val result =
            mfaService.beginEnrollment(
                userId = step.pending.userId,
                tenantId = step.tenant.id,
                issuer = step.tenant.displayName,
                ipAddress = call.request.origin.remoteAddress,
                userAgent = call.request.userAgent(),
                withRecoveryCodes = false,
            )
        val begun = (result as? MfaResult.Success)?.value
        val enrollmentId = begun?.enrollment?.id
        if (begun == null || enrollmentId == null) {
            // Already enrolled meanwhile, or the user is gone: a fresh sign-in sorts out which.
            call.clearPendingEnrollment(step.slug, secure)
            return call.respondRedirect("/t/${step.slug}/account/login")
        }
        call.writePendingEnrollment(step.pending.copy(enrollmentId = enrollmentId), encryptionService, secure)
        call.respondHtml(HttpStatusCode.OK, AuthView.mfaEnrollSetupPage(step.slug, step.ctx.viewContext, begun.totpUri))
    }

    suspend fun verify(step: EnrollmentStep) {
        val call = step.call
        val pending = step.pending
        val totpUri =
            pending
                .takeIf { !it.verified && it.enrollmentId != 0 }
                ?.let {
                    mfaService.pendingEnrollmentUri(
                        it.userId,
                        step.tenant.id,
                        it.enrollmentId,
                        step.tenant.displayName,
                    )
                }
                ?: return call.respondRedirect("/t/${step.slug}/mfa-enroll")

        suspend fun retry(
            status: HttpStatusCode,
            key: String,
        ) = call.respondHtml(
            status,
            AuthView.mfaEnrollSetupPage(step.slug, step.ctx.viewContext, totpUri, step.ctx.viewContext.t(key)),
        )

        val ipAddress = call.request.origin.remoteAddress
        val allowed =
            mfaRateLimiter.isAllowed("mfa:$ipAddress:${step.slug}") &&
                mfaRateLimiter.isAllowed("mfa-enroll:${pending.userId.value}")
        if (!allowed) return retry(HttpStatusCode.TooManyRequests, "MFA_ENROLL_TOO_MANY_ATTEMPTS")

        val verified =
            mfaService.verifyEnrollment(
                userId = pending.userId,
                code = call.receiveParameters()["code"]?.trim().orEmpty(),
                ipAddress = ipAddress,
                userAgent = call.request.userAgent(),
                enrollmentId = pending.enrollmentId,
            )
        if (verified is MfaResult.Failure) return retry(HttpStatusCode.Unauthorized, "MFA_ENROLL_INVALID_CODE")

        val recoveryCodes =
            (mfaService.issueRecoveryCodes(pending.userId, step.tenant.id) as? MfaResult.Success)?.value.orEmpty()
        call.writePendingEnrollment(pending.copy(verified = true), encryptionService, secure)
        call.respondHtml(HttpStatusCode.OK, AuthView.mfaEnrollDonePage(step.slug, step.ctx.viewContext, recoveryCodes))
    }

    suspend fun complete(step: EnrollmentStep) {
        val call = step.call
        // The cookie says the setup was verified; the repository has to agree before it counts.
        if (!step.pending.verified || !mfaService.shouldChallengeMfa(step.pending.userId)) {
            return call.respondRedirect("/t/${step.slug}/mfa-enroll")
        }
        call.clearPendingEnrollment(step.slug, secure)
        call.completeAuthorizationCodeFlow(
            slug = step.slug,
            userId = step.pending.userId,
            tenantId = step.tenant.id,
            oauthParams = step.oauthParams,
            ipAddress = call.request.origin.remoteAddress,
            authTime = Instant.now(),
            mfaCompleted = true,
            ssoTtlSeconds = ssoTtlSeconds,
            secure = secure,
            oauthService = oauthService,
            encryptionService = encryptionService,
            renderError = { message ->
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.mfaEnrollDonePage(step.slug, step.ctx.viewContext, error = message),
                )
            },
        )
    }
}
