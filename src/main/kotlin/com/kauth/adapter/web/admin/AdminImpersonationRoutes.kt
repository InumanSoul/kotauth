package com.kauth.adapter.web.admin

import com.kauth.adapter.web.portal.PortalSession
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminError
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.ImpersonationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.time.Instant

/**
 * Admin-side impersonation start endpoint.
 *
 * Lives inside `/admin/workspaces/{slug}/users/{userId}` under the existing
 * AdminUserRoutes scope, so the workspace + admin context is already
 * resolved by the time the handler runs.
 *
 * Writes the resulting PortalSession cookie scoped to `/`, redirects the
 * admin into the portal as the target user. The admin's AdminSession cookie
 * is untouched (different path scope) so the admin shell remains intact.
 */
fun Route.adminUserImpersonationRoute(
    impersonationService: ImpersonationService,
    sessionRepository: SessionRepository,
    userRepository: UserRepository,
) {
    post("/impersonate") {
        val ctx = call.adminContext()
        val userId =
            call.parameters.typedId("userId", ::UserId)
                ?: return@post call.respond(HttpStatusCode.BadRequest)

        val adminSessionId =
            ctx.session.adminSessionId?.let(::SessionId)
                ?: return@post call.respondRedirect(
                    "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=impersonation_failed",
                )

        // Replace semantics: end any prior impersonation owned by this admin
        // session before issuing a new one. Cheap and idempotent.
        sessionRepository.revokeAllByImpersonator(adminSessionId, Instant.now())

        val result =
            impersonationService.startImpersonation(
                adminUserId = UserId(ctx.session.userId),
                adminSessionId = adminSessionId,
                targetTenantId = ctx.workspace.id,
                targetUserId = userId,
                ipAddress = call.request.origin.remoteHost,
                userAgent = call.request.userAgent(),
            )

        when (result) {
            is AdminResult.Success -> {
                val target =
                    userRepository.findById(userId, ctx.workspace.id)
                        ?: return@post call.respondRedirect(
                            "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=impersonation_failed",
                        )

                call.sessions.set(
                    PortalSession(
                        userId = target.id!!.value,
                        tenantId = ctx.workspace.id.value,
                        tenantSlug = ctx.workspace.slug,
                        username = target.username,
                        portalSessionId = result.value.impersonationSessionId.value,
                        impersonatorAdminUserId = ctx.session.userId,
                        impersonatorAdminUsername = ctx.session.username,
                        impersonatorAdminSessionId = adminSessionId.value,
                    ),
                )
                call.respondRedirect("/t/${ctx.workspace.slug}/launcher")
            }

            is AdminResult.Failure -> {
                val flag =
                    when (val err = result.error) {
                        is AdminError.NotFound -> "impersonation_not_found"
                        is AdminError.Validation ->
                            when {
                                err.message.contains("disabled", ignoreCase = true) -> "impersonation_disabled"
                                err.message.contains("locked", ignoreCase = true) -> "impersonation_locked"
                                else -> "impersonation_failed"
                            }

                        is AdminError.Conflict -> "impersonation_failed"
                    }
                call.respondRedirect("/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=$flag")
            }
        }
    }
}

/**
 * Portal-side "End session" endpoint. Registered inside the existing
 * `/t/{slug}/account` route block so it shares the same scope as the rest
 * of the portal routes. The admin reaches it from the impersonation banner
 * that renders on every portal page when the PortalSession has
 * `impersonatorAdminSessionId` set.
 *
 * Revokes the impersonation session row, clears the portal cookie, and
 * sends the admin back to the user-detail page in the admin shell.
 */
fun Route.portalImpersonationStopRoute(impersonationService: ImpersonationService) {
    post("/impersonation/stop") {
        val portal =
            call.sessions.get<PortalSession>()
                ?: return@post call.respondRedirect("/")
        if (!portal.isImpersonation) {
            return@post call.respondRedirect("/")
        }

        val adminSessionId = SessionId(portal.impersonatorAdminSessionId!!)
        val impersonationSessionId =
            portal.portalSessionId
                ?.let(::SessionId)
                ?: return@post call.respondRedirect("/")

        impersonationService.stopImpersonation(
            adminUserId = UserId(portal.impersonatorAdminUserId!!),
            adminSessionId = adminSessionId,
            impersonationSessionId = impersonationSessionId,
            ipAddress = call.request.origin.remoteHost,
            userAgent = call.request.userAgent(),
        )

        call.sessions.clear<PortalSession>()
        call.respondRedirect(
            "/admin/workspaces/${portal.tenantSlug}/users/${portal.userId}?saved=impersonation_ended",
        )
    }
}
