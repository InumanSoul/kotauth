package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminService
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

internal fun Route.apiUserRoutes(
    adminService: AdminService,
    roleGroupService: RoleGroupService,
) {
    route("/users") {
        get {
            requireScope(call, ApiScope.USERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val search = call.request.queryParameters["search"]
            val users = adminService.listUsers(tenantId, search)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = users.map { it.toApiDto() },
                    meta = ApiMeta(total = users.size),
                ),
            )
        }

        post {
            requireScope(call, ApiScope.USERS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateUserRequest>()

            when (
                val result =
                    adminService.createUser(
                        tenantId = tenantId,
                        username = body.username,
                        email = body.email,
                        fullName = body.fullName,
                        password = body.password,
                    )
            ) {
                is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        route("/{userId}") {
            get {
                requireScope(call, ApiScope.USERS_READ) ?: return@get
                val tenantId = call.attributes[TenantIdAttr]
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@get call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid user ID",
                            "userId must be an integer.",
                        )
                val user =
                    when (val r = adminService.getUser(userId, tenantId)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure ->
                            return@get call.respondProblem(
                                HttpStatusCode.NotFound,
                                "User not found",
                                "No user with id $userId in this workspace.",
                            )
                    }
                call.respond(HttpStatusCode.OK, user.toApiDto())
            }

            put {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@put
                val tenantId = call.attributes[TenantIdAttr]
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@put call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid user ID",
                            "userId must be an integer.",
                        )
                val body = call.receive<UpdateUserRequest>()

                when (val result = adminService.updateUser(userId, tenantId, body.email, body.fullName)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            delete {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
                val tenantId = call.attributes[TenantIdAttr]
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@delete call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid user ID",
                            "userId must be an integer.",
                        )

                when (val result = adminService.setUserEnabled(userId, tenantId, false)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            route("/roles") {
                post("/{roleId}") {
                    requireScope(call, ApiScope.USERS_WRITE) ?: return@post
                    val tenantId = call.attributes[TenantIdAttr]
                    val userId =
                        call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                            ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Invalid user ID", "")
                    val roleId =
                        call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                            ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                    roleGroupService.assignRoleToUser(userId, roleId, tenantId)
                    call.respond(HttpStatusCode.NoContent, "")
                }

                delete("/{roleId}") {
                    requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
                    val tenantId = call.attributes[TenantIdAttr]
                    val userId =
                        call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                            ?: return@delete call.respondProblem(
                                HttpStatusCode.BadRequest,
                                "Invalid user ID",
                                "",
                            )
                    val roleId =
                        call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                            ?: return@delete call.respondProblem(
                                HttpStatusCode.BadRequest,
                                "Invalid role ID",
                                "",
                            )
                    roleGroupService.unassignRoleFromUser(userId, roleId, tenantId)
                    call.respond(HttpStatusCode.NoContent, "")
                }
            }
        }
    }
}
