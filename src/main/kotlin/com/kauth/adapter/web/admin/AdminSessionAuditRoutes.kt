package com.kauth.adapter.web.admin

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.SessionId
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.time.Instant

fun Route.adminSessionAuditRoutes(
    tenantRepository: TenantRepository,
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
) {
    // -------------------------------------------------------------------
    // Active sessions
    // -------------------------------------------------------------------

    get("/sessions") {
        val session = call.sessions.get<AdminSession>()!!
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val workspace =
            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
        val sessions = sessionRepository.findActiveByTenant(workspace.id)
        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.activeSessionsPage(workspace, sessions, wsPairs, session.username),
        )
    }

    post("/sessions/{sessionId}/revoke") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val sessionId =
            call.parameters["sessionId"]?.toIntOrNull()?.let { SessionId(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        sessionRepository.revoke(sessionId, Instant.now())
        call.respondRedirect("/admin/workspaces/$slug/sessions")
    }

    // -------------------------------------------------------------------
    // Audit log
    // -------------------------------------------------------------------

    get("/logs") {
        val session = call.sessions.get<AdminSession>()!!
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val workspace =
            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
        val page =
            call.request.queryParameters["page"]
                ?.toIntOrNull()
                ?.coerceAtLeast(1) ?: 1
        val eventTypeStr = call.request.queryParameters["event"]
        val eventType = eventTypeStr?.let { runCatching { AuditEventType.valueOf(it) }.getOrNull() }
        val pageSize = 50
        val offset = (page - 1) * pageSize
        val events =
            auditLogRepository.findByTenant(
                workspace.id,
                eventType,
                limit = pageSize,
                offset = offset,
            )
        val total = auditLogRepository.countByTenant(workspace.id, eventType)
        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.auditLogPage(
                workspace,
                events,
                wsPairs,
                session.username,
                page = page,
                totalPages = ((total + pageSize - 1) / pageSize).toInt(),
                eventTypeFilter = eventTypeStr,
            ),
        )
    }
}
