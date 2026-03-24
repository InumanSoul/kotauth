package com.kauth.adapter.web.portal

import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.MfaError
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SelfServiceResult
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.PortalClientProvisioning
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Self-service portal routes.
 *
 * URL structure under /t/{slug}/account/:
 *   GET  /login            — initiates OAuth Authorization Code + PKCE flow
 *   GET  /callback         — handles OAuth redirect, exchanges code for session
 *   POST /logout           — clears portal session and end-session at auth server
 *   GET  /profile          — view & edit profile (email, full name)
 *   POST /profile          — submit profile changes
 *   GET  /security         — change password + active sessions
 *   POST /change-password  — submit new password
 *   POST /sessions/{id}/revoke — revoke one session
 *   GET  /mfa              — MFA management (enroll, disable)
 *   POST /mfa/enroll|verify|disable — MFA operations
 *
 * Auth flow:
 *   1. GET /login redirects to the standard /t/{slug}/protocol/openid-connect/auth
 *      with PKCE (code_challenge/code_verifier). The verifier is stored in a
 *      short-lived HMAC-signed cookie to prevent CSRF.
 *   2. After login (+ MFA if required), the auth server redirects to /account/callback.
 *   3. /callback exchanges the code + verifier for tokens, decodes the userId from
 *      the access token's sub claim, and creates a PortalSession.
 *
 * MFA is handled by the standard auth flow (AuthRoutes.kt) — no duplicate MFA logic here.
 * Password expiry is enforced by AuthService.authenticate() — portal login benefits automatically.
 *
 * Auth guard: all /account/{path} routes except /login and /callback redirect to /account/login
 * if no valid PortalSession cookie is found.
 */
fun Route.portalRoutes(
    selfServiceService: UserSelfServiceService,
    tenantRepository: TenantRepository,
    mfaService: MfaService? = null,
    oauthService: OAuthService? = null,
    baseUrl: String = "",
    encryptionService: EncryptionService,
) {
    route("/t/{slug}/account") {
        // ------------------------------------------------------------------
        // Login — redirect to standard OAuth auth endpoint (PKCE)
        // ------------------------------------------------------------------

        get("/login") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)

            if (oauthService == null) {
                // Fallback: show the old login page if OAuth service is not wired
                val tenant = tenantRepository.findBySlug(slug)!!
                val error = call.request.queryParameters["error"]
                return@get call.respondHtml(
                    HttpStatusCode.OK,
                    PortalView.loginPage(slug, tenant.displayName, tenant.theme, error),
                )
            }

            // Generate PKCE verifier + challenge
            val verifier = generatePkceVerifier()
            val challenge = generatePkceChallenge(verifier)

            // Store the verifier in a short-lived signed cookie (5 min) to survive
            // the round-trip through the auth server
            val cookieVal = encryptionService.signCookie("$verifier|$slug|${System.currentTimeMillis()}")
            call.response.cookies.append(
                name = "KOTAUTH_PORTAL_PKCE",
                value = cookieVal,
                maxAge = 300L,
                httpOnly = true,
                path = "/t/$slug/account",
            )

            val redirectUri = "$baseUrl/t/$slug/account/callback"
            val authEndpoint = "/t/$slug/protocol/openid-connect/auth"
            val authUrl =
                buildString {
                    append(authEndpoint)
                    append("?response_type=code")
                    append("&client_id=").append(PortalClientProvisioning.PORTAL_CLIENT_ID)
                    append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
                    append("&scope=openid+profile+email")
                    append("&code_challenge=").append(challenge)
                    append("&code_challenge_method=S256")
                }
            call.respondRedirect(authUrl)
        }

        // ------------------------------------------------------------------
        // OAuth callback — exchange code for tokens, create portal session
        // ------------------------------------------------------------------

        get("/callback") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val code = call.request.queryParameters["code"]
            val error = call.request.queryParameters["error"]

            if (oauthService == null || code.isNullOrBlank()) {
                val desc = error ?: "Authentication failed. Please try again."
                return@get call.respondRedirect(
                    "/t/$slug/account/login?error=${encodeParam(desc)}",
                )
            }

            // Verify and extract PKCE verifier from signed cookie
            val rawPkce = call.request.cookies["KOTAUTH_PORTAL_PKCE"]
            if (rawPkce.isNullOrBlank()) {
                return@get call.respondRedirect(
                    "/t/$slug/account/login?error=${encodeParam("Session expired. Please try again.")}",
                )
            }
            val pkcePayload = encryptionService.verifyCookie(rawPkce)
            if (pkcePayload == null) {
                return@get call.respondRedirect(
                    "/t/$slug/account/login?error=${encodeParam("Invalid session. Please try again.")}",
                )
            }
            val pkceParts = pkcePayload.split("|")
            if (pkceParts.size != 3 || pkceParts[1] != slug) {
                return@get call.respondRedirect(
                    "/t/$slug/account/login?error=${encodeParam("Session mismatch. Please try again.")}",
                )
            }
            val verifier = pkceParts[0]
            val timestamp = pkceParts[2].toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - timestamp > 300_000) {
                return@get call.respondRedirect(
                    "/t/$slug/account/login?error=${encodeParam("Login session expired. Please try again.")}",
                )
            }

            // Clear the PKCE cookie — single-use
            call.response.cookies.append(
                name = "KOTAUTH_PORTAL_PKCE",
                value = "",
                maxAge = 0L,
                path = "/t/$slug/account",
                httpOnly = true,
            )

            val redirectUri = "$baseUrl/t/$slug/account/callback"
            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]

            val tokenResult =
                oauthService.exchangeAuthorizationCode(
                    tenantSlug = slug,
                    code = code,
                    clientId = PortalClientProvisioning.PORTAL_CLIENT_ID,
                    redirectUri = redirectUri,
                    codeVerifier = verifier,
                    clientSecret = null, // PUBLIC client — no secret
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                )

            if (tokenResult is OAuthResult.Failure) {
                return@get call.respondRedirect(
                    "/t/$slug/account/login?error=${encodeParam("Login failed: ${tokenResult.error}")}",
                )
            }

            // Decode userId and username from the access token payload (base64 JWT claim)
            val accessToken = (tokenResult as OAuthResult.Success).value.access_token
            val claims = decodeJwtPayload(accessToken)
            val userId = claims["sub"]?.toIntOrNull()
            val username = claims["preferred_username"] ?: ""
            val tenantObj = tenantRepository.findBySlug(slug)

            if (userId == null || tenantObj == null) {
                return@get call.respondRedirect(
                    "/t/$slug/account/login?error=${encodeParam("Could not establish portal session.")}",
                )
            }

            val latestSession =
                selfServiceService
                    .getActiveSessions(UserId(userId), tenantObj.id)
                    .maxByOrNull { it.createdAt }

            call.sessions.set(
                PortalSession(
                    userId = userId,
                    tenantId = tenantObj.id.value,
                    tenantSlug = slug,
                    username = username,
                    portalSessionId = latestSession?.id?.value,
                ),
            )

            // If the tenant requires MFA but this user hasn't enrolled yet, redirect
            // directly to the MFA setup page with a prominent notice instead of landing
            // on the profile page. The standard login flow normally blocks these users
            // outright, but for portal logins we bypass that gate (in AuthRoutes) and
            // handle it here as a softer, guided redirect.
            val mfaSetupNeeded =
                mfaService != null &&
                    tenantObj.mfaPolicy != "optional" &&
                    !mfaService.shouldChallengeMfa(UserId(userId))

            if (mfaSetupNeeded) {
                val notice =
                    encodeParam(
                        "⚠\uFE0F Multi-factor authentication is required for your account. " +
                            "Please set up an authenticator app below to keep your account secure.",
                    )
                call.respondRedirect("/t/$slug/account/mfa?notice=$notice")
            } else {
                call.respondRedirect("/t/$slug/account/profile")
            }
        }

        post("/logout") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.sessions.clear<PortalSession>()
            call.respondRedirect("/t/$slug/account/login")
        }

        // ------------------------------------------------------------------
        // Auth guard — everything below requires an active portal session
        // ------------------------------------------------------------------

        fun ApplicationCall.portalSession(slug: String): PortalSession? {
            val session = sessions.get<PortalSession>() ?: return null
            if (session.tenantSlug != slug) return null
            return session
        }

        // ------------------------------------------------------------------
        // Profile
        // ------------------------------------------------------------------

        get("/profile") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@get call.respondRedirect("/t/$slug/account/login")
            val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val successMsg = call.request.queryParameters["saved"]
            val errorMsg = call.request.queryParameters["error"]

            val user =
                when (val r = selfServiceService.getProfile(UserId(session.userId), TenantId(session.tenantId))) {
                    is SelfServiceResult.Success -> r.value
                    is SelfServiceResult.Failure -> null
                }

            call.respondHtml(
                HttpStatusCode.OK,
                PortalView.profilePage(
                    slug,
                    session,
                    tenant.theme,
                    tenant.displayName,
                    layout = tenant.portalConfig.layout,
                    successMsg = successMsg,
                    errorMsg = errorMsg,
                    email = user?.email ?: "",
                    fullName = user?.fullName ?: "",
                ),
            )
        }

        post("/profile") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val params = call.receiveParameters()
            val email = params["email"]?.trim() ?: ""
            val fullName = params["full_name"]?.trim() ?: ""

            when (
                val result =
                    selfServiceService.updateProfile(
                        UserId(session.userId),
                        TenantId(session.tenantId),
                        email,
                        fullName,
                    )
            ) {
                is SelfServiceResult.Success ->
                    call.respondRedirect("/t/$slug/account/profile?saved=true")
                is SelfServiceResult.Failure ->
                    call.respondRedirect("/t/$slug/account/profile?error=${encodeParam(result.error.message)}")
            }
        }

        post("/delete") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val confirmUsername = call.receiveParameters()["confirm_username"]?.trim() ?: ""

            if (!confirmUsername.equals(session.username, ignoreCase = true)) {
                return@post call.respondRedirect(
                    "/t/$slug/account/profile?error=${encodeParam("Username confirmation does not match.")}",
                )
            }

            when (val result = selfServiceService.disableAccount(UserId(session.userId), TenantId(session.tenantId))) {
                is SelfServiceResult.Success -> {
                    call.sessions.clear<PortalSession>()
                    call.respondRedirect(
                        "/t/$slug/account/login?error=${encodeParam("Your account has been deleted.")}",
                    )
                }
                is SelfServiceResult.Failure ->
                    call.respondRedirect("/t/$slug/account/profile?error=${encodeParam(result.error.message)}")
            }
        }

        // ------------------------------------------------------------------
        // Security (change password + sessions)
        // ------------------------------------------------------------------

        get("/security") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@get call.respondRedirect("/t/$slug/account/login")
            val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val sessions = selfServiceService.getActiveSessions(UserId(session.userId), TenantId(session.tenantId))
            val successMsg = call.request.queryParameters["saved"]
            val errorMsg = call.request.queryParameters["error"]

            call.respondHtml(
                HttpStatusCode.OK,
                PortalView.securityPage(
                    slug,
                    session,
                    tenant.theme,
                    tenant.displayName,
                    layout = tenant.portalConfig.layout,
                    sessions = sessions,
                    currentSessionId = session.portalSessionId,
                    successMsg = successMsg,
                    errorMsg = errorMsg,
                ),
            )
        }

        post("/change-password") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val params = call.receiveParameters()
            val current = params["current_password"] ?: ""
            val newPw = params["new_password"] ?: ""
            val confirm = params["confirm_password"] ?: ""

            when (
                val result =
                    selfServiceService.changePassword(
                        UserId(session.userId),
                        TenantId(session.tenantId),
                        current,
                        newPw,
                        confirm,
                    )
            ) {
                is SelfServiceResult.Success -> {
                    // All sessions revoked — clear portal cookie, redirect to login
                    call.sessions.clear<PortalSession>()
                    call.respondRedirect(
                        "/t/$slug/account/login?error=${encodeParam("Password changed. Please log in again.")}",
                    )
                }
                is SelfServiceResult.Failure ->
                    call.respondRedirect("/t/$slug/account/security?error=${encodeParam(result.error.message)}")
            }
        }

        post("/sessions/{sessionId}/revoke") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val sessionId =
                call.parameters["sessionId"]?.toIntOrNull()?.let { SessionId(it) }
                    ?: return@post call.respond(HttpStatusCode.BadRequest)

            selfServiceService.revokeSession(UserId(session.userId), TenantId(session.tenantId), sessionId)
            call.respondRedirect("/t/$slug/account/security?saved=true")
        }

        post("/sessions/revoke-others") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val keepId = session.portalSessionId?.let { SessionId(it) }
            if (keepId == null) {
                return@post call.respondRedirect(
                    "/t/$slug/account/security?error=${encodeParam("Could not identify current session.")}",
                )
            }
            selfServiceService.revokeOtherSessions(UserId(session.userId), TenantId(session.tenantId), keepId)
            call.respondRedirect("/t/$slug/account/security?saved=true")
        }

        // ------------------------------------------------------------------
        // MFA management page
        // ------------------------------------------------------------------

        get("/mfa") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@get call.respondRedirect("/t/$slug/account/login")
            val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val mfaEnabled = mfaService?.shouldChallengeMfa(UserId(session.userId)) ?: false
            val successMsg = call.request.queryParameters["success"]
            val errorMsg = call.request.queryParameters["error"]
            // notice = shown when the user was redirected here because MFA setup is required
            val noticeMsg = call.request.queryParameters["notice"]

            call.respondHtml(
                HttpStatusCode.OK,
                PortalView.mfaPage(
                    slug = slug,
                    session = session,
                    theme = tenant.theme,
                    workspaceName = tenant.displayName,
                    layout = tenant.portalConfig.layout,
                    mfaEnabled = mfaEnabled,
                    successMsg = successMsg,
                    errorMsg = errorMsg,
                    noticeMsg = noticeMsg,
                ),
            )
        }

        // ------------------------------------------------------------------
        // MFA self-service (JSON API, consumed by portal UI)
        // ------------------------------------------------------------------

        // POST /t/{slug}/account/mfa/enroll — start TOTP enrollment
        post("/mfa/enroll") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session =
                call.portalSession(slug)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))
            if (mfaService == null) return@post call.respond(HttpStatusCode.NotFound)

            val tenant = tenantRepository.findById(TenantId(session.tenantId))
            val issuer = tenant?.displayName ?: "KotAuth"

            when (val result = mfaService.beginEnrollment(UserId(session.userId), TenantId(session.tenantId), issuer)) {
                is MfaResult.Success ->
                    call.respond(
                        buildJsonObject {
                            put("totp_uri", result.value.totpUri)
                            putJsonArray("recovery_codes") {
                                result.value.recoveryCodes.forEach { add(it) }
                            }
                        },
                    )
                is MfaResult.Failure ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        buildJsonObject {
                            put("error", result.error.toCode())
                        },
                    )
            }
        }

        // POST /t/{slug}/account/mfa/verify — confirm enrollment with TOTP code
        post("/mfa/verify") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session =
                call.portalSession(slug)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))
            if (mfaService == null) return@post call.respond(HttpStatusCode.NotFound)

            val params = call.receiveParameters()
            val code = params["code"]?.trim() ?: ""

            when (val result = mfaService.verifyEnrollment(UserId(session.userId), code)) {
                is MfaResult.Success -> call.respond(mapOf("status" to "verified"))
                is MfaResult.Failure ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to result.error.toCode(),
                        ),
                    )
            }
        }

        // POST /t/{slug}/account/mfa/disable — remove MFA enrollment
        post("/mfa/disable") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session =
                call.portalSession(slug)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))
            if (mfaService == null) return@post call.respond(HttpStatusCode.NotFound)

            mfaService.disableMfa(UserId(session.userId), TenantId(session.tenantId))
            call.respond(mapOf("status" to "disabled"))
        }
    }
}

private fun MfaError.toCode(): String =
    when (this) {
        is MfaError.UserNotFound -> "user_not_found"
        is MfaError.TenantNotFound -> "tenant_not_found"
        is MfaError.AlreadyEnrolled -> "already_enrolled"
        is MfaError.NotEnrolled -> "not_enrolled"
        is MfaError.InvalidCode -> "invalid_code"
        is MfaError.NoRecoveryCodesLeft -> "no_recovery_codes"
    }

private fun encodeParam(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

// ============================================================================
// PKCE helpers — RFC 7636
// ============================================================================

/**
 * Generates a cryptographically random PKCE code_verifier.
 * Length: 43 characters (spec allows 43–128). Base64url-encoded, no padding.
 */
private fun generatePkceVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Derives the PKCE code_challenge from the verifier using S256 method:
 * code_challenge = BASE64URL(SHA256(ASCII(code_verifier)))
 */
private fun generatePkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

// ============================================================================
// JWT payload decoder — extract claims from an access token without verification
// (safe because we just issued the token ourselves in the callback flow)
// ============================================================================

/**
 * Decodes the payload section of a JWT and returns its claims as a flat string map.
 * This is NOT a security-sensitive verification — it is used only to read the sub
 * and preferred_username claims from a token that was just issued by us.
 *
 * Only handles simple string/number claims. Complex nested objects are ignored.
 * Returns an empty map on any parse failure.
 */
private fun decodeJwtPayload(jwt: String): Map<String, String> {
    return try {
        val parts = jwt.split(".")
        if (parts.size < 2) return emptyMap()
        val payload =
            String(
                Base64.getUrlDecoder().decode(
                    // JWT base64url payload may omit padding
                    parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='),
                ),
                Charsets.UTF_8,
            )
        // Minimal JSON parsing — extract "key":"value" and "key":number pairs
        val result = mutableMapOf<String, String>()
        val pattern = Regex("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+))")
        pattern.findAll(payload).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            if (value.isNotEmpty()) result[key] = value
        }
        result
    } catch (_: Exception) {
        emptyMap()
    }
}
