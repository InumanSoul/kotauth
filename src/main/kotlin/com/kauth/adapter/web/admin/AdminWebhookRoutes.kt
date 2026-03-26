package com.kauth.adapter.web.admin

import com.kauth.domain.service.WebhookService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

fun Route.adminWebhookRoutes(webhookService: WebhookService?) {
    get("/settings/webhooks") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val endpoints = webhookService?.listEndpoints(workspace.id) ?: emptyList()
        val deliveries = webhookService?.recentDeliveries(workspace.id) ?: emptyList()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.webhooksListPage(workspace, endpoints, deliveries, wsPairs, session.username),
        )
    }

    get("/settings/webhooks/new") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.createWebhookPage(workspace, wsPairs, session.username),
        )
    }

    post("/settings/webhooks") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val svc = webhookService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)

        val params = call.receiveParameters()
        val url = params["url"]?.trim() ?: ""
        val description = params["description"]?.trim() ?: ""
        val events = params.getAll("events")?.toSet() ?: emptySet()

        when (val result = svc.createEndpoint(workspace.id, url, events, description)) {
            is com.kauth.domain.service.WebhookResult.Success -> {
                val endpoints = svc.listEndpoints(workspace.id)
                val deliveries = svc.recentDeliveries(workspace.id)
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.webhooksListPage(
                        workspace,
                        endpoints,
                        deliveries,
                        wsPairs,
                        session.username,
                        newSecret = result.plaintextSecret,
                    ),
                )
            }
            is com.kauth.domain.service.WebhookResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.createWebhookPage(
                        workspace,
                        wsPairs,
                        session.username,
                        error = result.error,
                    ),
                )
            }
        }
    }

    post("/settings/webhooks/{endpointId}/toggle") {
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val endpointId =
            call.parameters["endpointId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        val params = call.receiveParameters()
        val enabled = params["enabled"] == "true"
        webhookService?.toggleEndpoint(endpointId, workspace.id, enabled)
        call.respondRedirect("/admin/workspaces/$slug/settings/webhooks")
    }

    post("/settings/webhooks/{endpointId}/delete") {
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val endpointId =
            call.parameters["endpointId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        webhookService?.deleteEndpoint(endpointId, workspace.id)
        call.respondRedirect("/admin/workspaces/$slug/settings/webhooks")
    }
}
