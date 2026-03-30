package com.kauth.adapter.web.admin

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.SessionId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.UserRepository
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
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
    userRepository: UserRepository,
    applicationRepository: ApplicationRepository,
) {
    // -------------------------------------------------------------------
    // Active sessions
    // -------------------------------------------------------------------

    get("/sessions") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val sessions = sessionRepository.findActiveByTenant(workspace.id)
        val sessionUserIds = sessions.mapNotNull { it.userId }.distinct()
        val sessionUserMap =
            sessionUserIds.associateWith { uid ->
                userRepository.findById(uid, workspace.id)?.username ?: uid.value.toString()
            }
        val sessionClientIds = sessions.mapNotNull { it.clientId }.distinct()
        val sessionClientMap: Map<ApplicationId, String> =
            sessionClientIds.associateWith { cid ->
                applicationRepository.findById(cid)?.name ?: cid.value.toString()
            }
        val wsPairs = call.attributes[WsPairsAttr]
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.activeSessionsPage(
                workspace,
                sessions,
                wsPairs,
                session.username,
                sessionUserMap,
                sessionClientMap,
            ),
        )
    }

    post("/sessions/{sessionId}/revoke") {
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
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
        val workspace = call.attributes[WorkspaceAttr]
        val page =
            call.request.queryParameters["page"]
                ?.toIntOrNull()
                ?.coerceAtLeast(1) ?: 1
        val eventTypeStr = call.request.queryParameters["event"]
        val eventType = eventTypeStr?.let { runCatching { AuditEventType.valueOf(it) }.getOrNull() }
        val pageSize = 20
        val offset = (page - 1) * pageSize
        val events =
            auditLogRepository.findByTenant(
                workspace.id,
                eventType,
                limit = pageSize,
                offset = offset,
            )
        val total = auditLogRepository.countByTenant(workspace.id, eventType)
        val auditUserIds = events.mapNotNull { it.userId }.distinct()
        val auditUserMap =
            auditUserIds.associateWith { uid ->
                userRepository.findById(uid, workspace.id)?.username ?: uid.value.toString()
            }
        val auditClientIds = events.mapNotNull { it.clientId }.distinct()
        val auditClientMap: Map<ApplicationId, String> =
            auditClientIds.associateWith { cid ->
                applicationRepository.findById(cid)?.name ?: cid.value.toString()
            }
        val wsPairs = call.attributes[WsPairsAttr]
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
                userMap = auditUserMap,
                clientMap = auditClientMap,
            ),
        )
    }
}
