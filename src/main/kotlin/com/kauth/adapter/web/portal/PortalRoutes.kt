package com.kauth.adapter.web.portal

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.AuthError
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.MfaError
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.SelfServiceError
import com.kauth.domain.service.SelfServiceResult
import com.kauth.domain.service.UserSelfServiceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * Self-service portal routes — Phase 3b.
 *
 * URL structure under /t/{slug}/account/:
 *   GET/POST /login        — portal authentication
 *   POST     /logout       — clear portal session
 *   GET      /profile      — view & edit profile (email, full name)
 *   POST     /profile      — submit profile changes
 *   GET      /security     — change password + active sessions
 *   POST     /change-password  — submit new password
 *   POST     /sessions/{id}/revoke — revoke one session
 *
 * Auth guard: all /account/[*] routes except /login redirect to /account/login
 * if no valid PortalSession cookie is found.
 *
 * NOTE (Phase 3b): Portal session is a simple cookie — see PortalSession.kt for
 * the design rationale and the planned Phase 5 upgrade path.
 */
fun Route.portalRoutes(
    authService         : AuthService,
    selfServiceService  : UserSelfServiceService,
    tenantRepository    : TenantRepository,
    mfaService          : MfaService? = null,  // Phase 3c — nullable for backward compat
    userRepository      : UserRepository? = null  // Phase 3c — needed for MFA challenge resolution
) {
    route("/t/{slug}/account") {

        // ------------------------------------------------------------------
        // Login / logout
        // ------------------------------------------------------------------

        get("/login") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val error = call.request.queryParameters["error"]
            call.respondHtml(HttpStatusCode.OK, PortalView.loginPage(slug, tenant.displayName, tenant.theme, error))
        }

        post("/login") {
            val slug     = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant   = tenantRepository.findBySlug(slug)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val params   = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val password = params["password"] ?: ""

            when (val result = authService.authenticate(slug, username, password)) {
                is AuthResult.Success -> {
                    val user = result.value

                    // Phase 3c: MFA challenge — if user has MFA enabled, redirect to challenge page
                    if (mfaService != null && mfaService.shouldChallengeMfa(user.id!!)) {
                        val mfaPending = "${user.id}|$slug|${System.currentTimeMillis()}"
                        call.response.cookies.append(
                            name     = "KOTAUTH_PORTAL_MFA_PENDING",
                            value    = mfaPending,
                            maxAge   = 300L,   // 5 minutes
                            httpOnly = true,
                            path     = "/t/$slug/account"
                        )
                        call.respondRedirect("/t/$slug/account/mfa-challenge")
                        return@post
                    }

                    call.sessions.set(PortalSession(
                        userId    = user.id!!,
                        tenantId  = user.tenantId,
                        tenantSlug = slug,
                        username  = user.username
                    ))
                    call.respondRedirect("/t/$slug/account/profile")
                }
                is AuthResult.Failure -> {
                    val msg = when (result.error) {
                        AuthError.InvalidCredentials -> "Invalid username or password."
                        AuthError.TenantNotFound     -> "Workspace not found."
                        else                         -> "Login failed. Please try again."
                    }
                    call.respondRedirect("/t/$slug/account/login?error=${encodeParam(msg)}")
                }
            }
        }

        // Phase 3c: MFA challenge page for portal login
        get("/mfa-challenge") {
            val slug   = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val pending = call.request.cookies["KOTAUTH_PORTAL_MFA_PENDING"]
            if (pending.isNullOrBlank()) {
                return@get call.respondRedirect("/t/$slug/account/login")
            }

            val error = call.request.queryParameters["error"]
            call.respondHtml(HttpStatusCode.OK,
                PortalView.mfaChallengePage(slug, tenant.displayName, tenant.theme, error))
        }

        post("/mfa-challenge") {
            val slug   = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val params = call.receiveParameters()
            val code   = params["code"]?.trim() ?: ""

            val pending = call.request.cookies["KOTAUTH_PORTAL_MFA_PENDING"]
            if (pending.isNullOrBlank()) {
                return@post call.respondRedirect("/t/$slug/account/login")
            }

            val parts = pending.split("|")
            if (parts.size != 3) {
                return@post call.respondRedirect("/t/$slug/account/login")
            }
            val userId    = parts[0].toIntOrNull() ?: return@post call.respondRedirect("/t/$slug/account/login")
            val pendSlug  = parts[1]
            val timestamp = parts[2].toLongOrNull() ?: 0L

            // Validate: slug must match and token must be < 5 minutes old
            if (pendSlug != slug || System.currentTimeMillis() - timestamp > 300_000) {
                call.response.cookies.append(
                    name = "KOTAUTH_PORTAL_MFA_PENDING", value = "", maxAge = 0L,
                    path = "/t/$slug/account", httpOnly = true
                )
                return@post call.respondRedirect("/t/$slug/account/login?error=${encodeParam("MFA session expired. Please log in again.")}")
            }

            if (mfaService == null) {
                return@post call.respondRedirect("/t/$slug/account/login")
            }

            // Try TOTP code first, then recovery code
            val mfaResult = if (code.length == 6 && code.all { it.isDigit() }) {
                mfaService.verifyTotp(userId, code)
            } else {
                mfaService.verifyRecoveryCode(userId, code)
            }

            when (mfaResult) {
                is MfaResult.Failure -> {
                    call.respondRedirect("/t/$slug/account/mfa-challenge?error=${encodeParam("Invalid verification code. Please try again.")}")
                }
                is MfaResult.Success -> {
                    // Clear MFA pending cookie
                    call.response.cookies.append(
                        name = "KOTAUTH_PORTAL_MFA_PENDING", value = "", maxAge = 0L,
                        path = "/t/$slug/account", httpOnly = true
                    )

                    // Fetch user and create portal session
                    val user = userRepository?.findById(userId)
                    if (user == null) {
                        return@post call.respondRedirect("/t/$slug/account/login?error=${encodeParam("User not found.")}")
                    }
                    call.sessions.set(PortalSession(
                        userId     = user.id!!,
                        tenantId   = user.tenantId,
                        tenantSlug = slug,
                        username   = user.username
                    ))
                    call.respondRedirect("/t/$slug/account/profile")
                }
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
            val slug    = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@get call.respondRedirect("/t/$slug/account/login")
            val tenant  = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val user    = selfServiceService.getActiveSessions(session.userId, session.tenantId)
                .let { null } // just to get user — fetch below
            val userObj = run {
                // We need the user — fetch active sessions gives us sessions, not user.
                // Delegate to the port directly via selfServiceService's session list.
                // Actually: the service doesn't expose a getUser. We route back through sessions.
                // Simplest: read user from sessions (already authenticated).
                // For now pull sessions to confirm we're still live, then we just need the user object.
                // We use the user retrieved at login — stored in session.
                session
            }
            val successMsg = call.request.queryParameters["saved"]
            val errorMsg   = call.request.queryParameters["error"]

            call.respondHtml(HttpStatusCode.OK,
                PortalView.profilePage(slug, session, tenant.theme, tenant.displayName, successMsg, errorMsg)
            )
        }

        post("/profile") {
            val slug    = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val params  = call.receiveParameters()
            val email    = params["email"]?.trim() ?: ""
            val fullName = params["full_name"]?.trim() ?: ""

            when (val result = selfServiceService.updateProfile(session.userId, session.tenantId, email, fullName)) {
                is SelfServiceResult.Success ->
                    call.respondRedirect("/t/$slug/account/profile?saved=true")
                is SelfServiceResult.Failure ->
                    call.respondRedirect("/t/$slug/account/profile?error=${encodeParam(result.error.message)}")
            }
        }

        // ------------------------------------------------------------------
        // Security (change password + sessions)
        // ------------------------------------------------------------------

        get("/security") {
            val slug    = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@get call.respondRedirect("/t/$slug/account/login")
            val tenant  = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val sessions = selfServiceService.getActiveSessions(session.userId, session.tenantId)
            val successMsg = call.request.queryParameters["saved"]
            val errorMsg   = call.request.queryParameters["error"]

            call.respondHtml(HttpStatusCode.OK,
                PortalView.securityPage(slug, session, tenant.theme, tenant.displayName, sessions, successMsg, errorMsg)
            )
        }

        post("/change-password") {
            val slug    = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val params  = call.receiveParameters()
            val current = params["current_password"] ?: ""
            val newPw   = params["new_password"] ?: ""
            val confirm = params["confirm_password"] ?: ""

            when (val result = selfServiceService.changePassword(session.userId, session.tenantId, current, newPw, confirm)) {
                is SelfServiceResult.Success -> {
                    // All sessions revoked — clear portal cookie, redirect to login
                    call.sessions.clear<PortalSession>()
                    call.respondRedirect("/t/$slug/account/login?error=${encodeParam("Password changed. Please log in again.")}")
                }
                is SelfServiceResult.Failure ->
                    call.respondRedirect("/t/$slug/account/security?error=${encodeParam(result.error.message)}")
            }
        }

        post("/sessions/{sessionId}/revoke") {
            val slug      = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session   = call.portalSession(slug) ?: return@post call.respondRedirect("/t/$slug/account/login")
            val sessionId = call.parameters["sessionId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            selfServiceService.revokeSession(session.userId, session.tenantId, sessionId)
            call.respondRedirect("/t/$slug/account/security?saved=true")
        }

        // ------------------------------------------------------------------
        // MFA management page — Phase 3c
        // ------------------------------------------------------------------

        get("/mfa") {
            val slug       = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val session    = call.portalSession(slug) ?: return@get call.respondRedirect("/t/$slug/account/login")
            val tenant     = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val mfaEnabled = mfaService?.shouldChallengeMfa(session.userId) ?: false
            val successMsg = call.request.queryParameters["success"]
            val errorMsg   = call.request.queryParameters["error"]

            call.respondHtml(
                HttpStatusCode.OK,
                PortalView.mfaPage(
                    slug          = slug,
                    session       = session,
                    theme         = tenant.theme,
                    workspaceName = tenant.displayName,
                    mfaEnabled    = mfaEnabled,
                    successMsg    = successMsg,
                    errorMsg      = errorMsg
                )
            )
        }

        // ------------------------------------------------------------------
        // MFA self-service — Phase 3c (JSON API, consumed by portal UI)
        // ------------------------------------------------------------------

        // POST /t/{slug}/account/mfa/enroll — start TOTP enrollment
        post("/mfa/enroll") {
            val slug    = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))
            if (mfaService == null) return@post call.respond(HttpStatusCode.NotFound)

            val tenant = tenantRepository.findById(session.tenantId)
            val issuer = tenant?.displayName ?: "KotAuth"

            when (val result = mfaService.beginEnrollment(session.userId, session.tenantId, issuer)) {
                is MfaResult.Success -> call.respond(buildJsonObject {
                    put("totp_uri", result.value.totpUri)
                    putJsonArray("recovery_codes") {
                        result.value.recoveryCodes.forEach { add(it) }
                    }
                })
                is MfaResult.Failure -> call.respond(HttpStatusCode.Conflict, buildJsonObject {
                    put("error", result.error.toCode())
                })
            }
        }

        // POST /t/{slug}/account/mfa/verify — confirm enrollment with TOTP code
        post("/mfa/verify") {
            val slug    = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))
            if (mfaService == null) return@post call.respond(HttpStatusCode.NotFound)

            val params = call.receiveParameters()
            val code   = params["code"]?.trim() ?: ""

            when (val result = mfaService.verifyEnrollment(session.userId, code)) {
                is MfaResult.Success -> call.respond(mapOf("status" to "verified"))
                is MfaResult.Failure -> call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to result.error.toCode()
                ))
            }
        }

        // POST /t/{slug}/account/mfa/disable — remove MFA enrollment
        post("/mfa/disable") {
            val slug    = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = call.portalSession(slug) ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))
            if (mfaService == null) return@post call.respond(HttpStatusCode.NotFound)

            mfaService.disableMfa(session.userId, session.tenantId)
            call.respond(mapOf("status" to "disabled"))
        }
    }
}

private fun MfaError.toCode(): String = when (this) {
    is MfaError.UserNotFound        -> "user_not_found"
    is MfaError.TenantNotFound      -> "tenant_not_found"
    is MfaError.AlreadyEnrolled     -> "already_enrolled"
    is MfaError.NotEnrolled         -> "not_enrolled"
    is MfaError.InvalidCode         -> "invalid_code"
    is MfaError.NoRecoveryCodesLeft -> "no_recovery_codes"
}

private fun encodeParam(value: String) =
    java.net.URLEncoder.encode(value, "UTF-8")
