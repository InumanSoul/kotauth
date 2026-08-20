package com.kauth.adapter.web.api

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.RoleId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.ApplicationManagementService
import com.kauth.domain.service.RoleGroupService
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

internal fun Route.apiApplicationRoutes(
    applicationRepository: ApplicationRepository,
    applicationManagementService: ApplicationManagementService,
    roleGroupService: RoleGroupService,
) {
    route("/applications") {
        get {
            requireScope(call, ApiScope.APPLICATIONS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val apps = applicationRepository.findByTenantId(tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = apps.map { it.toApiDto() },
                    meta = ApiMeta(total = apps.size),
                ),
            )
        }

        get("/{appId}") {
            requireScope(call, ApiScope.APPLICATIONS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
            val app =
                applicationRepository.findById(appId)
                    ?: return@get call.respondProblem(
                        HttpStatusCode.NotFound,
                        "Application not found",
                        "No application with id $appId.",
                    )
            if (app.tenantId != tenantId) {
                return@get call.respondProblem(HttpStatusCode.NotFound, "Application not found", "")
            }
            call.respond(HttpStatusCode.OK, app.toApiDto())
        }

        post {
            requireScope(call, ApiScope.APPLICATIONS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateApplicationRequest>()

            when (
                val result =
                    applicationManagementService.createApplication(
                        tenantId = tenantId,
                        clientId = body.clientId,
                        name = body.name,
                        description = body.description,
                        accessType = body.accessType,
                        redirectUris = body.redirectUris,
                        // Grants default until the request model accepts them; the creation secret isn't surfaced yet.
                        grantTypes = GrantType.defaultsFor(AccessType.fromValue(body.accessType)),
                    )
            ) {
                is AdminResult.Failure -> call.respondAdminError(result.error)
                is AdminResult.Success -> {
                    val app = result.value.application
                    val secret =
                        if (app.accessType == AccessType.CONFIDENTIAL) {
                            when (val rotate = applicationManagementService.regenerateClientSecret(app.id, tenantId)) {
                                is AdminResult.Success -> rotate.value
                                is AdminResult.Failure -> {
                                    // Application was just created; regeneration failing here is a bug — fail loudly.
                                    return@post call.respondAdminError(rotate.error)
                                }
                            }
                        } else {
                            null
                        }
                    call.respond(
                        HttpStatusCode.Created,
                        CreateApplicationResponse(application = app.toApiDto(), clientSecret = secret),
                    )
                }
            }
        }

        put("/{appId}") {
            requireScope(call, ApiScope.APPLICATIONS_WRITE) ?: return@put
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
            val body = call.receive<UpdateApplicationRequest>()
            when (
                val result =
                    applicationManagementService.updateApplication(
                        appId = appId,
                        tenantId = tenantId,
                        name = body.name,
                        description = body.description,
                        accessType = body.accessType,
                        redirectUris = body.redirectUris,
                        audience = body.audience,
                    )
            ) {
                is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        delete("/{appId}") {
            requireScope(call, ApiScope.APPLICATIONS_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid application ID",
                        "appId must be an integer.",
                    )
            when (val result = applicationManagementService.deleteApplication(appId, tenantId)) {
                is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        post("/{appId}/regenerate-secret") {
            requireScope(call, ApiScope.APPLICATIONS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@post call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid application ID",
                        "appId must be an integer.",
                    )
            when (val result = applicationManagementService.regenerateClientSecret(appId, tenantId)) {
                is AdminResult.Success ->
                    call.respond(HttpStatusCode.Created, ClientSecretResponse(clientSecret = result.value))
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        get("/{appId}/default-roles") {
            requireScope(call, ApiScope.APPLICATIONS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
            when (val result = roleGroupService.getClientDefaultRoles(tenantId, appId)) {
                is AdminResult.Success ->
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            data = result.value.map { it.toApiDto() },
                            meta = ApiMeta(total = result.value.size),
                        ),
                    )
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        // Full-set replace — simpler for API consumers than per-role add/remove.
        put("/{appId}/default-roles") {
            requireScope(call, ApiScope.APPLICATIONS_WRITE) ?: return@put
            val tenantId = call.attributes[TenantIdAttr]
            val appId =
                call.parameters["appId"]?.toIntOrNull()?.let { ApplicationId(it) }
                    ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
            val body = call.receive<SetDefaultRolesRequest>()
            when (
                val result =
                    roleGroupService.setClientDefaultRoles(tenantId, appId, body.roleIds.map { RoleId(it) })
            ) {
                is AdminResult.Success ->
                    when (val refreshed = roleGroupService.getClientDefaultRoles(tenantId, appId)) {
                        is AdminResult.Success ->
                            call.respond(
                                HttpStatusCode.OK,
                                ApiResponse(
                                    data = refreshed.value.map { it.toApiDto() },
                                    meta = ApiMeta(total = refreshed.value.size),
                                ),
                            )
                        is AdminResult.Failure -> call.respondAdminError(refreshed.error)
                    }
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }
    }
}
