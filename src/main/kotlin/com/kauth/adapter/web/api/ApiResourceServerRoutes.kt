package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.service.ResourceServerResult
import com.kauth.domain.service.ResourceServerService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

internal fun Route.apiResourceServerRoutes(
    resourceServerService: ResourceServerService,
    applicationRepository: ApplicationRepository,
) {
    route("/resource-servers") {
        get {
            requireScope(call, ApiScope.RESOURCE_SERVERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val servers = resourceServerService.list(tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = servers.map { it.toApiDto() },
                    meta = ApiMeta(total = servers.size, offset = 0, limit = servers.size),
                ),
            )
        }

        post {
            requireScope(call, ApiScope.RESOURCE_SERVERS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateResourceServerRequest>()
            when (
                val result =
                    resourceServerService.create(
                        tenantId = tenantId,
                        identifier = body.identifier,
                        name = body.name,
                        description = body.description,
                        scopes = body.scopes,
                    )
            ) {
                is ResourceServerResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                is ResourceServerResult.Failure -> call.respondResourceServerError(result.error)
            }
        }

        route("/{id}") {
            get {
                requireScope(call, ApiScope.RESOURCE_SERVERS_READ) ?: return@get
                val tenantId = call.attributes[TenantIdAttr]
                val id =
                    call.parameters["id"]?.toIntOrNull()?.let { ResourceServerId(it) }
                        ?: return@get call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid ID",
                            "id must be an integer.",
                        )
                val server =
                    resourceServerService.get(tenantId, id)
                        ?: return@get call.respondProblem(
                            HttpStatusCode.NotFound,
                            "Not Found",
                            "Resource server not found.",
                        )
                call.respond(HttpStatusCode.OK, server.toApiDto())
            }

            put {
                requireScope(call, ApiScope.RESOURCE_SERVERS_WRITE) ?: return@put
                val tenantId = call.attributes[TenantIdAttr]
                val id =
                    call.parameters["id"]?.toIntOrNull()?.let { ResourceServerId(it) }
                        ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid ID", "")
                val body = call.receive<UpdateResourceServerRequest>()
                when (
                    val result =
                        resourceServerService.update(
                            tenantId = tenantId,
                            id = id,
                            name = body.name,
                            description = body.description,
                            scopes = body.scopes,
                        )
                ) {
                    is ResourceServerResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                    is ResourceServerResult.Failure -> call.respondResourceServerError(result.error)
                }
            }

            delete {
                requireScope(call, ApiScope.RESOURCE_SERVERS_WRITE) ?: return@delete
                val tenantId = call.attributes[TenantIdAttr]
                val id =
                    call.parameters["id"]?.toIntOrNull()?.let { ResourceServerId(it) }
                        ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invalid ID", "")
                when (val result = resourceServerService.delete(tenantId, id)) {
                    is ResourceServerResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                    is ResourceServerResult.Failure -> call.respondResourceServerError(result.error)
                }
            }
        }
    }

    // Client-scoped authorization edges (matches the service's existing API shape)
    route("/applications/{appId}/authorized-resource-servers") {
        get {
            requireScope(call, ApiScope.RESOURCE_SERVERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
            val app =
                applicationRepository.findById(appId)
                    ?: return@get call.respondProblem(HttpStatusCode.NotFound, "Not Found", "Application not found.")
            if (app.tenantId != tenantId) {
                return@get call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Not Found",
                    "Application not found in this workspace.",
                )
            }
            val servers = resourceServerService.listAuthorized(appId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = servers.map { it.toApiDto() },
                    meta = ApiMeta(total = servers.size, offset = 0, limit = servers.size),
                ),
            )
        }

        put {
            requireScope(call, ApiScope.RESOURCE_SERVERS_WRITE) ?: return@put
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
            val app =
                applicationRepository.findById(appId)
                    ?: return@put call.respondProblem(HttpStatusCode.NotFound, "Not Found", "Application not found.")
            if (app.tenantId != tenantId) {
                return@put call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Not Found",
                    "Application not found in this workspace.",
                )
            }
            val body = call.receive<SetAuthorizedResourceServersRequest>()
            when (
                val result =
                    resourceServerService.setAuthorized(appId, body.resourceServerIds.map { ResourceServerId(it) })
            ) {
                is ResourceServerResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                is ResourceServerResult.Failure -> call.respondResourceServerError(result.error)
            }
        }
    }
}
