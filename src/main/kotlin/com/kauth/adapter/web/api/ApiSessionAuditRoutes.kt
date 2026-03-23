package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.SessionRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Instant

internal fun Route.apiSessionAuditRoutes(
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
) {
    route("/sessions") {
        get {
            requireScope(call, ApiScope.SESSIONS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val sessions = sessionRepository.findActiveByTenant(tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = sessions.map { it.toApiDto() },
                    meta = ApiMeta(total = sessions.size),
                ),
            )
        }

        delete("/{sessionId}") {
            requireScope(call, ApiScope.SESSIONS_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val sessionId =
                call.parameters["sessionId"]?.toIntOrNull()?.let { SessionId(it) }
                    ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invalid session ID", "")
            val session =
                sessionRepository.findById(sessionId)
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.NotFound,
                        "Session not found",
                        "No session with id $sessionId.",
                    )
            if (session.tenantId != tenantId) {
                return@delete call.respondProblem(HttpStatusCode.NotFound, "Session not found", "")
            }
            sessionRepository.revoke(sessionId, Instant.now())
            call.respond(HttpStatusCode.NoContent, "")
        }
    }

    route("/audit-logs") {
        get {
            requireScope(call, ApiScope.AUDIT_LOGS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val params = call.request.queryParameters
            val limit = (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
            val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
            val userId = params["userId"]?.toIntOrNull()?.let { UserId(it) }
            val eventType =
                params["eventType"]?.let { name ->
                    runCatching { AuditEventType.valueOf(name) }.getOrNull()
                }

            val events = auditLogRepository.findByTenant(tenantId, eventType, userId, limit, offset)
            val total = auditLogRepository.countByTenant(tenantId, eventType, userId)

            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = events.map { it.toApiDto() },
                    meta = ApiMeta(total = total.toInt(), offset = offset, limit = limit),
                ),
            )
        }
    }
}
