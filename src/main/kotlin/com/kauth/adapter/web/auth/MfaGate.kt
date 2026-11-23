package com.kauth.adapter.web.auth

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.UserId
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import java.time.Instant

internal const val MFA_PENDING_COOKIE = "KOTAUTH_MFA_PENDING"
private const val MFA_PENDING_TTL_SECONDS = 300L

internal enum class MfaStep { PROCEED, CHALLENGE, ENROLLMENT_REQUIRED }

/**
 * Decides what a sign-in owes the second factor once its first factor has passed. Every sign-in
 * path that ends in an authorization code goes through here, so a method the user did not pick
 * can never be a way around the one they enrolled.
 */
internal class MfaGate(
    private val mfaService: MfaService?,
    private val roleRepository: RoleRepository?,
) {
    fun stepFor(
        userId: UserId,
        tenant: Tenant,
        clientId: String?,
    ): MfaStep {
        val service = mfaService ?: return MfaStep.PROCEED
        if (service.shouldChallengeMfa(userId)) return MfaStep.CHALLENGE
        val policy = tenant.mfaPolicy
        if (policy == "optional") return MfaStep.PROCEED
        val roles =
            if (policy == "required_admins") {
                roleRepository?.resolveEffectiveRoles(userId, tenant.id).orEmpty()
            } else {
                emptyList()
            }
        return if (service.isMfaRequired(policy, roles)) MfaStep.ENROLLMENT_REQUIRED else MfaStep.PROCEED
    }
}

/**
 * Finishes a sign-in whose first factor has passed: challenges the second factor when the user
 * has one, sends them to set one up when the workspace requires one they lack, and otherwise
 * issues the authorization code. Returns the URL to send the browser to, or null once
 * [renderError] has responded.
 *
 * [secondFactorDone] is for a first factor that already proves two factors, such as a passkey
 * with user verification.
 */
internal suspend fun ApplicationCall.signInRedirectUrl(
    gate: MfaGate,
    slug: String,
    userId: UserId,
    tenant: Tenant,
    oauthParams: AuthView.OAuthParams,
    ipAddress: String?,
    ssoTtlSeconds: Long,
    secure: Boolean,
    oauthService: OAuthService,
    encryptionService: EncryptionService,
    renderError: suspend (message: String) -> Unit,
    secondFactorDone: Boolean = false,
): String? {
    // Refuse a user of another workspace before anything else, so not even the challenge starts.
    if (!oauthService.userBelongsToTenant(userId, tenant.id)) {
        renderError(EnglishStrings.SIGN_IN_WRONG_WORKSPACE)
        return null
    }
    if (!secondFactorDone) {
        when (gate.stepFor(userId, tenant, oauthParams.clientId)) {
            MfaStep.ENROLLMENT_REQUIRED ->
                return startMfaEnrollment(slug, userId, oauthParams, encryptionService, secure)
            MfaStep.CHALLENGE -> return startMfaChallenge(slug, userId, oauthParams, encryptionService, secure)
            MfaStep.PROCEED -> Unit
        }
    }
    return buildAuthorizationCodeRedirectUrl(
        slug = slug,
        userId = userId,
        tenantId = tenant.id,
        oauthParams = oauthParams,
        ipAddress = ipAddress,
        authTime = Instant.now(),
        mfaCompleted = secondFactorDone,
        ssoTtlSeconds = ssoTtlSeconds,
        secure = secure,
        oauthService = oauthService,
        encryptionService = encryptionService,
        renderError = renderError,
    )
}

/** [signInRedirectUrl] for callers that answer with a redirect. */
internal suspend fun ApplicationCall.finishSignIn(
    gate: MfaGate,
    slug: String,
    userId: UserId,
    tenant: Tenant,
    oauthParams: AuthView.OAuthParams,
    ipAddress: String?,
    ssoTtlSeconds: Long,
    secure: Boolean,
    oauthService: OAuthService,
    encryptionService: EncryptionService,
    renderError: suspend (message: String) -> Unit,
) {
    val url =
        signInRedirectUrl(
            gate = gate,
            slug = slug,
            userId = userId,
            tenant = tenant,
            oauthParams = oauthParams,
            ipAddress = ipAddress,
            ssoTtlSeconds = ssoTtlSeconds,
            secure = secure,
            oauthService = oauthService,
            encryptionService = encryptionService,
            renderError = renderError,
        ) ?: return
    respondRedirect(url)
}

private fun ApplicationCall.startMfaChallenge(
    slug: String,
    userId: UserId,
    oauthParams: AuthView.OAuthParams,
    encryptionService: EncryptionService,
    secure: Boolean,
): String {
    response.cookies.append(
        name = MFA_PENDING_COOKIE,
        value = encryptionService.signCookie("${userId.value}|$slug|${System.currentTimeMillis()}"),
        maxAge = MFA_PENDING_TTL_SECONDS,
        httpOnly = true,
        secure = secure,
        path = "/t/$slug",
    )
    // The challenge page reads the OAuth request back from this cookie. Social sign-in carries it
    // through the provider round trip in its own state, so it is written fresh here for every path.
    setAuthContextCookie(oauthParams, slug, encryptionService, secure)
    return "/t/$slug/mfa-challenge"
}
