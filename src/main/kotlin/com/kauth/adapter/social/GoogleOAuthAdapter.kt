package com.kauth.adapter.social

import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialUserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Social provider adapter for Google OAuth2 / OIDC.
 *
 * Endpoints:
 *   Authorization : https://accounts.google.com/o/oauth2/v2/auth
 *   Token         : https://oauth2.googleapis.com/token
 *   UserInfo      : https://openidconnect.googleapis.com/v1/userinfo
 *
 * Scopes requested: openid email profile
 *
 * Implementation notes:
 *   - Uses java.net.http.HttpClient (JDK 11+) — no additional Gradle dependencies.
 *   - JSON parsing via kotlinx.serialization.json (already on the classpath via Ktor).
 *   - Follows RFC 6749 authorization code grant with PKCE-ready redirect URI handling.
 */
class GoogleOAuthAdapter : SocialProviderPort {

    override val provider = SocialProvider.GOOGLE

    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val AUTH_ENDPOINT  = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val USERINFO_URL   = "https://openidconnect.googleapis.com/v1/userinfo"
        private val DEFAULT_SCOPES       = listOf("openid", "email", "profile")
    }

    override fun buildAuthorizationUrl(
        clientId    : String,
        redirectUri : String,
        state       : String,
        scopes      : List<String>
    ): String {
        val effectiveScopes = if (scopes.isEmpty()) DEFAULT_SCOPES else scopes
        val params = mapOf(
            "client_id"     to clientId,
            "redirect_uri"  to redirectUri,
            "response_type" to "code",
            "scope"         to effectiveScopes.joinToString(" "),
            "state"         to state,
            "access_type"   to "online",
            "prompt"        to "select_account"
        )
        return "$AUTH_ENDPOINT?${params.toQueryString()}"
    }

    override fun exchangeCodeForProfile(
        code         : String,
        redirectUri  : String,
        clientId     : String,
        clientSecret : String
    ): SocialUserProfile {
        val tokenResponse = exchangeCode(code, redirectUri, clientId, clientSecret)
        return fetchUserInfo(tokenResponse.accessToken)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private data class TokenResponse(val accessToken: String)

    private fun exchangeCode(
        code         : String,
        redirectUri  : String,
        clientId     : String,
        clientSecret : String
    ): TokenResponse {
        val body = mapOf(
            "code"          to code,
            "client_id"     to clientId,
            "client_secret" to clientSecret,
            "redirect_uri"  to redirectUri,
            "grant_type"    to "authorization_code"
        ).toFormBody()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_ENDPOINT))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("Google token exchange failed: HTTP {} — {}", response.statusCode(), response.body())
            throw RuntimeException("Google token exchange returned HTTP ${response.statusCode()}")
        }

        val parsed = json.parseToJsonElement(response.body()).jsonObject
        val accessToken = parsed["access_token"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Google token response missing access_token")

        return TokenResponse(accessToken)
    }

    private fun fetchUserInfo(accessToken: String): SocialUserProfile {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(USERINFO_URL))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("Google userinfo fetch failed: HTTP {} — {}", response.statusCode(), response.body())
            throw RuntimeException("Google userinfo endpoint returned HTTP ${response.statusCode()}")
        }

        val obj = json.parseToJsonElement(response.body()).jsonObject
        return SocialUserProfile(
            providerUserId = obj["sub"]?.jsonPrimitive?.content
                ?: throw RuntimeException("Google userinfo missing 'sub' claim"),
            email          = obj["email"]?.jsonPrimitive?.content,
            name           = obj["name"]?.jsonPrimitive?.content,
            emailVerified  = obj["email_verified"]?.jsonPrimitive?.booleanOrNull ?: false,
            avatarUrl      = obj["picture"]?.jsonPrimitive?.content
        )
    }
}

// ---------------------------------------------------------------------------
// Shared URL helpers — package-private
// ---------------------------------------------------------------------------

internal fun Map<String, String>.toQueryString(): String =
    entries.joinToString("&") { (k, v) ->
        "${k.urlEncode()}=${v.urlEncode()}"
    }

internal fun Map<String, String>.toFormBody(): String = toQueryString()

internal fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8)
