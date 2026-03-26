package com.kauth.adapter.web.auth

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.OAuthError
import com.kauth.domain.service.SocialLoginError
import com.kauth.domain.service.SocialLoginNeedsRegistration
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/**
 * Tenant context resolved once per request by the auth route intercept.
 *
 * [tenant] is nullable — some auth pages render a default theme when the
 * tenant slug does not match. Handlers that require a non-null tenant
 * (e.g., OAuth protocol endpoints) check `ctx.tenant ?: return 404`.
 */
data class AuthTenantContext(
    val slug: String,
    val tenant: Tenant?,
    val theme: TenantTheme,
    val workspaceName: String,
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
        is AuthError.PasswordExpired -> "Your password has expired. Please reset it."
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
