package com.kauth.adapter.web.auth

import com.kauth.adapter.web.ViewContext
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.OAuthError
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SocialLoginError
import com.kauth.domain.service.SocialLoginNeedsRegistration
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.util.AttributeKey
import java.time.Instant

data class AuthTenantContext(
    val slug: String,
    val tenant: Tenant?,
    val theme: TenantTheme,
    val workspaceName: String,
    val viewContext: ViewContext,
)

internal val AuthTenantAttr = AttributeKey<AuthTenantContext>("AuthTenantContext")

internal suspend fun oauthError(
    call: ApplicationCall,
    error: String,
    description: String,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) {
    call.respond(
        status,
        mapOf(
            "error" to error,
            "error_description" to description,
        ),
    )
}

internal fun extractBearerToken(call: ApplicationCall): String? {
    val auth = call.request.headers["Authorization"] ?: return null
    if (!auth.startsWith("Bearer ", ignoreCase = true)) return null
    return auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
}

internal fun extractClientCredentials(
    call: ApplicationCall,
    params: Parameters,
): Pair<String?, String?> {
    val auth = call.request.headers["Authorization"]
    if (auth != null && auth.startsWith("Basic ", ignoreCase = true)) {
        val decoded =
            java.util.Base64
                .getDecoder()
                .decode(auth.removePrefix("Basic ").trim())
                .toString(Charsets.UTF_8)
        val sep = decoded.indexOf(':')
        if (sep > 0) {
            return decoded.substring(0, sep) to decoded.substring(sep + 1)
        }
    }
    return params["client_id"] to params["client_secret"]
}

internal fun Parameters.toOAuthParams() =
    AuthView.OAuthParams(
        responseType = this["response_type"],
        clientId = this["oauth_client_id"] ?: this["client_id"],
        redirectUri = this["redirect_uri"],
        scope = this["scope"],
        state = this["state"],
        codeChallenge = this["code_challenge"],
        codeChallengeMethod = this["code_challenge_method"],
        nonce = this["nonce"],
    )

// -- Server-side OAuth context cookie ------------------------------------------

private const val AUTH_CONTEXT_COOKIE = "KOTAUTH_AUTH_CONTEXT"

/**
 * Stores OAuth params in a signed cookie scoped to `/t/{slug}`.
 * Replaces hidden form fields — survives page refreshes, URL changes, and incognito mode.
 */
internal fun ApplicationCall.setAuthContextCookie(
    params: AuthView.OAuthParams,
    slug: String,
    encryptionService: EncryptionService,
    secure: Boolean = false,
    otpChallengeId: String? = null,
    otpEmail: String? = null,
) {
    val payload =
        listOf(
            params.responseType ?: "",
            params.clientId ?: "",
            params.redirectUri ?: "",
            params.scope ?: "",
            params.state ?: "",
            params.codeChallenge ?: "",
            params.codeChallengeMethod ?: "",
            params.nonce ?: "",
            System.currentTimeMillis().toString(),
            otpChallengeId ?: "",
            otpEmail ?: "",
        ).joinToString("|")
    response.cookies.append(
        name = AUTH_CONTEXT_COOKIE,
        value = encryptionService.signCookie(payload),
        maxAge = 300L,
        httpOnly = true,
        secure = secure,
        path = "/t/$slug",
    )
}

/** Reads and validates the OAuth context cookie. Returns null if missing, expired, or tampered. */
internal fun ApplicationCall.getAuthContext(encryptionService: EncryptionService): AuthView.OAuthParams? {
    val raw = request.cookies[AUTH_CONTEXT_COOKIE] ?: return null
    val payload = encryptionService.verifyCookie(raw) ?: return null
    val parts = payload.split("|")
    if (parts.size < 9) return null
    val timestamp = parts[8].toLongOrNull() ?: return null
    if (System.currentTimeMillis() - timestamp > 300_000) return null
    val params =
        AuthView.OAuthParams(
            responseType = parts[0].ifBlank { null },
            clientId = parts[1].ifBlank { null },
            redirectUri = parts[2].ifBlank { null },
            scope = parts[3].ifBlank { null },
            state = parts[4].ifBlank { null },
            codeChallenge = parts[5].ifBlank { null },
            codeChallengeMethod = parts[6].ifBlank { null },
            nonce = parts[7].ifBlank { null },
        )
    return if (params.isOAuthFlow) params else null
}

data class OtpFlowContext(
    val challengeId: String,
    val email: String,
)

/** Reads the OTP challenge ID + email carried alongside OAuth params in the auth-context cookie. */
internal fun ApplicationCall.getOtpFlowContext(encryptionService: EncryptionService): OtpFlowContext? {
    val raw = request.cookies[AUTH_CONTEXT_COOKIE] ?: return null
    val payload = encryptionService.verifyCookie(raw) ?: return null
    val parts = payload.split("|")
    if (parts.size < 11) return null
    val timestamp = parts[8].toLongOrNull() ?: return null
    if (System.currentTimeMillis() - timestamp > 300_000) return null
    val challengeId = parts[9].ifBlank { null } ?: return null
    val email = parts[10].ifBlank { null } ?: return null
    return OtpFlowContext(challengeId, email)
}

/** Clears the auth context cookie after successful code issuance. */
internal fun ApplicationCall.clearAuthContextCookie(slug: String) {
    response.cookies.append(
        name = AUTH_CONTEXT_COOKIE,
        value = "",
        maxAge = 0L,
        httpOnly = true,
        path = "/t/$slug",
    )
}

// -- SSO session cookie (OIDC silent auth) -------------------------------------

private const val SSO_COOKIE = "KOTAUTH_SSO"
private const val SSO_COOKIE_VERSION = "v1"

/**
 * Witness of a successful interactive authentication, scoped to `/t/{slug}`.
 *
 * Read by GET /authorize when honoring `prompt=none` to issue an authorization
 * code without re-prompting the user (OIDC Core §3.1.2.1). The `authTime` value
 * is the moment the user proved possession of their credentials — for MFA
 * flows it is the MFA completion time (Keycloak convention), not the
 * first-factor time. It surfaces as the `auth_time` claim in subsequent ID
 * tokens, and is the value `max_age` is checked against.
 */
internal data class SsoCookieData(
    val userId: Int,
    val tenantId: Int,
    val authTime: Instant,
    val mfaCompleted: Boolean,
    val expiresAt: Instant,
)

internal fun ApplicationCall.setSsoCookie(
    data: SsoCookieData,
    slug: String,
    encryptionService: EncryptionService,
    secure: Boolean = false,
) {
    val payload =
        listOf(
            SSO_COOKIE_VERSION,
            data.userId.toString(),
            data.tenantId.toString(),
            data.authTime.epochSecond.toString(),
            if (data.mfaCompleted) "1" else "0",
            data.expiresAt.epochSecond.toString(),
        ).joinToString("|")
    val maxAge = (data.expiresAt.epochSecond - Instant.now().epochSecond).coerceAtLeast(0L)
    response.cookies.append(
        name = SSO_COOKIE,
        value = encryptionService.signCookie(payload),
        maxAge = maxAge,
        httpOnly = true,
        secure = secure,
        path = "/t/$slug",
    )
}

internal fun ApplicationCall.getSsoCookie(encryptionService: EncryptionService): SsoCookieData? {
    val raw = request.cookies[SSO_COOKIE] ?: return null
    val payload = encryptionService.verifyCookie(raw) ?: return null
    val parts = payload.split("|")
    if (parts.size != 6 || parts[0] != SSO_COOKIE_VERSION) return null
    val userId = parts[1].toIntOrNull() ?: return null
    val tenantId = parts[2].toIntOrNull() ?: return null
    val authTime = parts[3].toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
    val mfaCompleted =
        when (parts[4]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
    val expiresAt = parts[5].toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
    if (Instant.now().isAfter(expiresAt)) return null
    return SsoCookieData(userId, tenantId, authTime, mfaCompleted, expiresAt)
}

internal fun ApplicationCall.clearSsoCookie(slug: String) {
    response.cookies.append(
        name = SSO_COOKIE,
        value = "",
        maxAge = 0L,
        httpOnly = true,
        path = "/t/$slug",
    )
}

internal fun parseQueryStringToOAuthParams(qs: String): AuthView.OAuthParams {
    if (qs.isBlank()) return AuthView.OAuthParams()
    val normalized = if (qs.startsWith("?")) qs.substring(1) else qs
    if (normalized.isBlank()) return AuthView.OAuthParams()
    val map =
        normalized
            .split("&")
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx < 0) {
                    null
                } else {
                    val k = java.net.URLDecoder.decode(it.substring(0, idx), "UTF-8")
                    val v = java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                    k to v
                }
            }.toMap()
    return AuthView.OAuthParams(
        responseType = map["response_type"],
        clientId = map["oauth_client_id"] ?: map["client_id"],
        redirectUri = map["redirect_uri"],
        scope = map["scope"],
        state = map["state"],
        codeChallenge = map["code_challenge"],
        codeChallengeMethod = map["code_challenge_method"],
        nonce = map["nonce"],
    )
}

internal fun encodeParam(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

// -- Error message mappers ------------------------------------------------

internal fun OAuthError.toErrorCode(): String =
    when (this) {
        is OAuthError.TenantNotFound -> "invalid_request"
        is OAuthError.InvalidClient -> "invalid_client"
        is OAuthError.InvalidGrant -> "invalid_grant"
        is OAuthError.InvalidRequest -> "invalid_request"
        is OAuthError.InvalidRedirectUri -> "invalid_request"
        is OAuthError.PkceRequired -> "invalid_request"
        is OAuthError.UnsupportedGrantType -> "unsupported_grant_type"
    }

internal fun OAuthError.toDescription(): String =
    when (this) {
        is OAuthError.TenantNotFound -> "Tenant not found"
        is OAuthError.InvalidClient -> this.reason
        is OAuthError.InvalidGrant -> this.reason
        is OAuthError.InvalidRequest -> this.reason
        is OAuthError.InvalidRedirectUri -> "Invalid redirect_uri: ${this.uri}"
        is OAuthError.PkceRequired -> "PKCE is required for public clients"
        is OAuthError.UnsupportedGrantType -> "Unsupported grant type"
    }

internal fun AuthError.toMessage(): String =
    when (this) {
        is AuthError.InvalidCredentials -> "Invalid username or password."
        is AuthError.TenantNotFound -> "Tenant not found."
        is AuthError.RegistrationDisabled -> "Registration is not enabled for this tenant."
        is AuthError.UserAlreadyExists -> "That username is already taken."
        is AuthError.EmailAlreadyExists -> "An account with that email already exists."
        is AuthError.WeakPassword -> "Password must be at least $minLength characters."
        is AuthError.ValidationError -> this.message
        // Collapsed to the generic message — these states would otherwise let an attacker
        // enumerate accounts (lockout, pending invite, expired/forced-change flags).
        is AuthError.PasswordExpired -> "Invalid username or password."
        is AuthError.AccountLocked -> "Invalid username or password."
        is AuthError.PendingSetup -> "Invalid username or password."
        is AuthError.PasswordChangeRequired -> "Invalid username or password."
        is AuthError.PasswordLoginDisabled ->
            "Password sign-in is disabled for this workspace. " +
                "Use the email link option or sign in with a configured social provider."
    }

internal fun SocialLoginError.toMessage(): String =
    @Suppress("ktlint:standard:max-line-length")
    when (this) {
        is SocialLoginError.TenantNotFound -> "Tenant not found."
        is SocialLoginError.ProviderNotConfigured ->
            "Social login with this provider is not configured for this tenant."
        is SocialLoginError.EmailNotProvided ->
            "Your social account did not provide an email address. Please use username/password login or grant email access."
        is SocialLoginError.UserDisabled -> "Your account has been disabled."
        is SocialLoginError.AccountCreationFailed -> "Failed to create an account. Please try again or contact support."
        is SocialLoginError.RegistrationDisabled -> "Account registration is not enabled for this workspace."
        is SocialLoginError.UsernameConflict -> "That username is already taken. Please choose a different one."
        is SocialLoginError.LinkRequiresEmailVerification ->
            "This email is already in use. Verify it with your social provider, then sign in again."
        is SocialLoginError.InvalidUsername -> this.reason
        is SocialLoginError.ProviderError ->
            "An error occurred communicating with the identity provider. Please try again."
        is SocialLoginError.InternalError -> "An internal error occurred. Please try again."
    }

// -- Social pending registration cookie -----------------------------------

internal data class SocialPendingData(
    val provider: com.kauth.domain.model.SocialProvider,
    val slug: String,
    val providerUserId: String,
    val email: String,
    val name: String?,
    val avatarUrl: String?,
    val emailVerified: Boolean,
    val oauthParamsRaw: String,
)

internal fun buildSocialPendingPayload(
    data: SocialLoginNeedsRegistration,
    slug: String,
    oauthParamsRaw: String,
): String {
    val enc =
        java.util.Base64
            .getUrlEncoder()
            .withoutPadding()

    fun String?.b64() = enc.encodeToString((this ?: "").toByteArray(Charsets.UTF_8))
    return listOf(
        data.provider.value,
        slug,
        data.providerUserId.b64(),
        data.email.b64(),
        data.name.b64(),
        data.avatarUrl.b64(),
        data.emailVerified.toString(),
        oauthParamsRaw.b64(),
        System.currentTimeMillis().toString(),
    ).joinToString("|")
}

internal fun parseSocialPendingCookie(
    rawCookie: String?,
    encryptionService: EncryptionService,
): SocialPendingData? {
    if (rawCookie.isNullOrBlank()) return null
    val payload = encryptionService.verifyCookie(rawCookie) ?: return null
    val parts = payload.split("|")
    if (parts.size < 9) return null

    val timestamp = parts[8].toLongOrNull() ?: return null
    if (System.currentTimeMillis() - timestamp > 600_000) return null

    return try {
        val dec = java.util.Base64.getUrlDecoder()

        fun decode(s: String) = String(dec.decode(s), Charsets.UTF_8)

        val provider =
            com.kauth.domain.model.SocialProvider
                .fromValueOrNull(parts[0]) ?: return null
        val slug = parts[1]
        val providerUserId = decode(parts[2])
        val email = decode(parts[3])
        val name = decode(parts[4]).ifBlank { null }
        val avatarUrl = decode(parts[5]).ifBlank { null }
        val emailVerified = parts[6].toBooleanStrictOrNull() ?: false
        val oauthParamsRaw = decode(parts[7])

        if (email.isBlank() || providerUserId.isBlank()) return null

        SocialPendingData(
            provider = provider,
            slug = slug,
            providerUserId = providerUserId,
            email = email,
            name = name,
            avatarUrl = avatarUrl,
            emailVerified = emailVerified,
            oauthParamsRaw = oauthParamsRaw,
        )
    } catch (_: Exception) {
        null
    }
}

// -- Authorization-code flow completion ----------------------------------------

/**
 * Shared "issue authorization code and redirect" finalization step.
 *
 * Called from any authentication-success point in an OAuth flow:
 *   - Password login (see [oauthProtocolRoutes])
 *   - MFA challenge success (see [mfaRoutes])
 *   - Magic-link consumption (see [magicLinkRoutes])
 *
 * Flow:
 *   1. Validates that [oauthParams] carries a `clientId` + `redirectUri` (400 otherwise).
 *   2. Requests an authorization code from [oauthService] for [userId].
 *   3. On success: clears the auth-context cookie, redirects to
 *      `redirectUri?code=…&state=…`.
 *   4. On failure: invokes [renderError] with a human-readable message so the
 *      caller can render its context-appropriate error page (login, mfa, etc.)
 *
 * [renderError] is a `suspend` callback because error rendering typically
 * involves `call.respondHtml(...)`.
 */
internal suspend fun ApplicationCall.completeAuthorizationCodeFlow(
    slug: String,
    userId: UserId,
    tenantId: TenantId,
    oauthParams: AuthView.OAuthParams,
    ipAddress: String?,
    authTime: Instant,
    mfaCompleted: Boolean,
    ssoTtlSeconds: Long,
    secure: Boolean,
    oauthService: OAuthService,
    encryptionService: EncryptionService,
    renderError: suspend (message: String) -> Unit,
) {
    val clientId =
        oauthParams.clientId
            ?: return respond(HttpStatusCode.BadRequest, "Missing client_id")
    val redirectUri =
        oauthParams.redirectUri
            ?: return respond(HttpStatusCode.BadRequest, "Missing redirect_uri")

    setSsoCookie(
        SsoCookieData(
            userId = userId.value,
            tenantId = tenantId.value,
            authTime = authTime,
            mfaCompleted = mfaCompleted,
            expiresAt = Instant.now().plusSeconds(ssoTtlSeconds),
        ),
        slug = slug,
        encryptionService = encryptionService,
        secure = secure,
    )

    when (
        val codeResult =
            oauthService.issueAuthorizationCode(
                tenantSlug = slug,
                userId = userId,
                clientId = clientId,
                redirectUri = redirectUri,
                scopes = oauthParams.scope ?: "openid",
                codeChallenge = oauthParams.codeChallenge,
                codeChallengeMethod = oauthParams.codeChallengeMethod,
                nonce = oauthParams.nonce,
                state = oauthParams.state,
                ipAddress = ipAddress,
                authTime = authTime,
            )
    ) {
        is OAuthResult.Success -> {
            clearAuthContextCookie(slug)
            val code = codeResult.value.code
            val state = oauthParams.state
            val redirect =
                buildString {
                    append(redirectUri)
                    append("?code=").append(code)
                    if (!state.isNullOrBlank()) append("&state=").append(state)
                }
            respondRedirect(redirect)
        }
        is OAuthResult.Failure -> {
            renderError(codeResult.error.toDescription())
        }
    }
}
