package com.kauth.adapter.social

import com.kauth.domain.model.SocialProvider
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialUserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Social provider adapter for GitHub OAuth2.
 *
 * Endpoints:
 *   Authorization : https://github.com/login/oauth/authorize
 *   Token         : https://github.com/login/oauth/access_token
 *   User          : https://api.github.com/user
 *   Emails        : https://api.github.com/user/emails  (for private email addresses)
 *
 * Scopes requested: read:user user:email
 *
 * Email handling:
 *   GitHub does not always return an email in the /user endpoint when the user has
 *   set their email to private. In that case we fall back to /user/emails to fetch
 *   the primary, verified email address.
 *
 * Implementation notes:
 *   - Uses java.net.http.HttpClient (JDK 11+) — no additional Gradle dependencies.
 *   - JSON parsing via kotlinx.serialization.json (already on the classpath via Ktor).
 */
class GitHubOAuthAdapter : SocialProviderPort {
    override val provider = SocialProvider.GITHUB

    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val AUTH_ENDPOINT = "https://github.com/login/oauth/authorize"
        private const val TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
        private const val USER_URL = "https://api.github.com/user"
        private const val EMAILS_URL = "https://api.github.com/user/emails"
        private val DEFAULT_SCOPES = listOf("read:user", "user:email")
    }

    override fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        scopes: List<String>,
    ): String {
        val effectiveScopes = if (scopes.isEmpty()) DEFAULT_SCOPES else scopes
        val params =
            mapOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "scope" to effectiveScopes.joinToString(" "),
                "state" to state,
            )
        return "$AUTH_ENDPOINT?${params.toQueryString()}"
    }

    override fun exchangeCodeForProfile(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
    ): SocialUserProfile {
        val accessToken = exchangeCode(code, redirectUri, clientId, clientSecret)
        return fetchProfile(accessToken)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun exchangeCode(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
    ): String {
        val body =
            mapOf(
                "code" to code,
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "redirect_uri" to redirectUri,
            ).toFormBody()

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json") // GitHub returns form-encoded by default
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("GitHub token exchange failed: HTTP {} — {}", response.statusCode(), response.body())
            throw RuntimeException("GitHub token exchange returned HTTP ${response.statusCode()}")
        }

        val parsed = json.parseToJsonElement(response.body()).jsonObject
        if (parsed["error"] != null) {
            val err = parsed["error"]?.jsonPrimitive?.content
            val desc = parsed["error_description"]?.jsonPrimitive?.content ?: ""
            throw RuntimeException("GitHub OAuth error: $err — $desc")
        }
        return parsed["access_token"]?.jsonPrimitive?.content
            ?: throw RuntimeException("GitHub token response missing access_token")
    }

    private fun fetchProfile(accessToken: String): SocialUserProfile {
        val userObj = githubGet(USER_URL, accessToken).jsonObject

        val id =
            userObj["id"]?.jsonPrimitive?.content
                ?: throw RuntimeException("GitHub user response missing 'id'")

        // GitHub returns `"email": null` (JSON null) for users with a private email.
        // In kotlinx.serialization, JsonNull.content == "null" (the string), which is
        // NOT blank — so we must explicitly reject the "null" string to avoid propagating
        // it as a real email address into the registration form.
        val publicEmail =
            userObj["email"]
                ?.jsonPrimitive
                ?.content
                ?.let { if (it == "null") null else it.ifBlank { null } }
        val name =
            userObj["name"]
                ?.jsonPrimitive
                ?.content
                ?.let { if (it == "null") null else it.ifBlank { null } }
                ?: userObj["login"]?.jsonPrimitive?.content
        val avatarUrl = userObj["avatar_url"]?.jsonPrimitive?.content

        // If public email is null, fetch the primary verified email from /user/emails
        val email = publicEmail ?: fetchPrimaryEmail(accessToken)

        return SocialUserProfile(
            providerUserId = id,
            email = email,
            name = name,
            emailVerified = email != null, // GitHub emails are verified by definition
            avatarUrl = avatarUrl,
        )
    }

    /**
     * Fetches the primary verified email from /user/emails.
     * Returns null if none can be found (user has no verified email on GitHub).
     */
    private fun fetchPrimaryEmail(accessToken: String): String? =
        try {
            val arr = githubGet(EMAILS_URL, accessToken).jsonArray
            // Prefer primary + verified; fall back to any verified email
            arr
                .map { it.jsonObject }
                .filter { it["verified"]?.jsonPrimitive?.booleanOrNull == true }
                .sortedByDescending { it["primary"]?.jsonPrimitive?.booleanOrNull == true }
                .firstOrNull()
                ?.get("email")
                ?.jsonPrimitive
                ?.content
        } catch (e: Exception) {
            log.debug("GitHub /user/emails fetch failed: ${e.message}")
            null
        }

    private fun githubGet(
        url: String,
        accessToken: String,
    ) = json.parseToJsonElement(apiGet(url, accessToken))

    private fun apiGet(
        url: String,
        accessToken: String,
    ): String {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("GitHub API $url returned HTTP ${response.statusCode()}")
        }
        return response.body()
    }
}
