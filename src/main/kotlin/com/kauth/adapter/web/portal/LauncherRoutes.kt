package com.kauth.adapter.web.portal

import com.kauth.adapter.web.ViewContext
import com.kauth.adapter.web.auth.resolveLocale
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TranslationPort
import com.kauth.domain.service.LauncherService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

/**
 * App launcher route — top-level under /t/{slug}/launcher.
 *
 * Sits outside /t/{slug}/account on purpose: the launcher is an organization
 * dashboard, not an account-management page. It still shares the portal shell
 * (sidebar/tabnav) and reuses the [PortalSession] cookie.
 *
 * Auth guard: redirects to /t/{slug}/account/login when no valid portal
 * session is present (same path the rest of the portal uses to bootstrap a
 * session via OAuth + PKCE).
 */
fun Route.launcherRoutes(
    launcherService: LauncherService,
    tenantRepository: TenantRepository,
    translationPort: TranslationPort,
    sessionRepository: SessionRepository? = null,
) {
    get("/t/{slug}/launcher") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
        val session =
            call.activePortalSession(slug, sessionRepository)
                ?: return@get call.respondRedirect("/t/$slug/account/login")

        val apps = launcherService.resolveLauncherApps(UserId(session.userId), TenantId(session.tenantId))

        val ctx =
            ViewContext(
                theme = tenant.theme,
                workspaceName = tenant.displayName,
                locale = call.resolveLocale(tenant, translationPort),
                translator = translationPort,
            )

        call.respondHtml(
            HttpStatusCode.OK,
            PortalView.launcherPage(
                slug = slug,
                session = session,
                ctx = ctx,
                layout = tenant.portalConfig.layout,
                apps = apps,
            ),
        )
    }
}

/**
 * Resolves an active portal session for [slug], validating that the
 * session's underlying database session has not been revoked.
 * Mirrors the helper inside [portalRoutes] so launcher routes can live
 * outside the /account prefix.
 */
private fun ApplicationCall.activePortalSession(
    slug: String,
    sessionRepository: SessionRepository?,
): PortalSession? {
    val session = sessions.get<PortalSession>() ?: return null
    if (session.tenantSlug != slug) return null
    val dbSessionId = session.portalSessionId
    if (dbSessionId != null && sessionRepository != null) {
        val dbSession = sessionRepository.findById(SessionId(dbSessionId))
        if (dbSession == null || dbSession.revokedAt != null) {
            sessions.clear<PortalSession>()
            return null
        }
    }
    return session
}
