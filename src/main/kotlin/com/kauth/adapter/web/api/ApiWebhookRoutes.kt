package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.WebhookEventType
import com.kauth.domain.service.WebhookResult
import com.kauth.domain.service.WebhookService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.apiWebhookRoutes(webhookService: WebhookService) {
    route("/webhooks") {
        get {
            requireScope(call, ApiScope.WEBHOOKS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val endpoints = webhookService.listEndpoints(tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = endpoints.map { it.toApiDto() },
                    meta = ApiMeta(total = endpoints.size, offset = 0, limit = endpoints.size),
                ),
            )
        }

        post {
            requireScope(call, ApiScope.WEBHOOKS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateWebhookRequest>()

            // Reject unknown event names with a 422 up-front — the service silently
            // ignores unrecognised strings by filtering to an empty set, which
            // gives a confusing "successful but subscribed to nothing" outcome.
            val invalidEvents = body.events.filter { WebhookEventType.fromValue(it) == null }
            if (invalidEvents.isNotEmpty()) {
                return@post call.respondProblem(
                    HttpStatusCode.UnprocessableEntity,
                    "Invalid event names",
                    "Unknown event(s): ${invalidEvents.joinToString(", ")}. Valid values: " +
                        WebhookEventType.entries.joinToString(", ") { it.value } + ".",
                )
            }
            val events = body.events.mapNotNull(WebhookEventType::fromValue).toSet()

            when (val result = webhookService.createEndpoint(tenantId, body.url, events, body.description)) {
                is WebhookResult.Success ->
                    call.respond(
                        HttpStatusCode.Created,
                        CreateWebhookResponse(
                            endpoint = result.endpoint.toApiDto(),
                            secret = result.plaintextSecret,
                        ),
                    )
                is WebhookResult.Failure ->
                    call.respondProblem(
                        HttpStatusCode.UnprocessableEntity,
                        "Validation Error",
                        result.error,
                    )
            }
        }

        delete("/{endpointId}") {
            requireScope(call, ApiScope.WEBHOOKS_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val endpointId =
                call.parameters["endpointId"]?.toIntOrNull()
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid webhook ID",
                        "endpointId must be an integer.",
                    )
            webhookService.deleteEndpoint(endpointId, tenantId)
            call.respond(HttpStatusCode.NoContent, "")
        }
    }
}
