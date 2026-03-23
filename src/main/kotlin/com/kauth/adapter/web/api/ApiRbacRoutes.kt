package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.UserId
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.service.AdminResult
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

internal fun Route.apiRbacRoutes(
    roleRepository: RoleRepository,
    groupRepository: GroupRepository,
    roleGroupService: RoleGroupService,
) {
    route("/roles") {
        get {
            requireScope(call, ApiScope.ROLES_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val roles = roleRepository.findByTenantId(tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = roles.map { it.toApiDto() },
                    meta = ApiMeta(total = roles.size),
                ),
            )
        }

        post {
            requireScope(call, ApiScope.ROLES_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateRoleRequest>()
            val roleScope =
                body.scope?.let { s -> RoleScope.entries.firstOrNull { it.value == s.lowercase() } }
                    ?: RoleScope.TENANT
            when (
                val result =
                    roleGroupService.createRole(
                        tenantId,
                        body.name,
                        body.description,
                        roleScope,
                        null,
                    )
            ) {
                is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        route("/{roleId}") {
            get {
                requireScope(call, ApiScope.ROLES_READ) ?: return@get
                val tenantId = call.attributes[TenantIdAttr]
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                val role =
                    roleRepository.findById(roleId)
                        ?: return@get call.respondProblem(
                            HttpStatusCode.NotFound,
                            "Role not found",
                            "No role with id $roleId.",
                        )
                if (role.tenantId != tenantId) {
                    return@get call.respondProblem(HttpStatusCode.NotFound, "Role not found", "")
                }
                call.respond(HttpStatusCode.OK, role.toApiDto())
            }

            put {
                requireScope(call, ApiScope.ROLES_WRITE) ?: return@put
                val tenantId = call.attributes[TenantIdAttr]
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                val body = call.receive<UpdateRoleRequest>()
                when (val result = roleGroupService.updateRole(roleId, tenantId, body.name, body.description)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            delete {
                requireScope(call, ApiScope.ROLES_WRITE) ?: return@delete
                val tenantId = call.attributes[TenantIdAttr]
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                when (val result = roleGroupService.deleteRole(roleId, tenantId)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }
        }
    }

    route("/groups") {
        get {
            requireScope(call, ApiScope.GROUPS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val groups = groupRepository.findByTenantId(tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = groups.map { it.toApiDto() },
                    meta = ApiMeta(total = groups.size),
                ),
            )
        }

        post {
            requireScope(call, ApiScope.GROUPS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateGroupRequest>()
            when (
                val result =
                    roleGroupService.createGroup(
                        tenantId,
                        body.name,
                        body.description,
                        body.parentGroupId?.let { GroupId(it) },
                    )
            ) {
                is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        route("/{groupId}") {
            get {
                requireScope(call, ApiScope.GROUPS_READ) ?: return@get
                val tenantId = call.attributes[TenantIdAttr]
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid group ID", "")
                val group =
                    groupRepository.findById(groupId)
                        ?: return@get call.respondProblem(
                            HttpStatusCode.NotFound,
                            "Group not found",
                            "No group with id $groupId.",
                        )
                if (group.tenantId != tenantId) {
                    return@get call.respondProblem(HttpStatusCode.NotFound, "Group not found", "")
                }
                call.respond(HttpStatusCode.OK, group.toApiDto())
            }

            put {
                requireScope(call, ApiScope.GROUPS_WRITE) ?: return@put
                val tenantId = call.attributes[TenantIdAttr]
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid group ID", "")
                val body = call.receive<UpdateGroupRequest>()
                when (
                    val result =
                        roleGroupService.updateGroup(
                            groupId,
                            tenantId,
                            body.name,
                            body.description,
                        )
                ) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            delete {
                requireScope(call, ApiScope.GROUPS_WRITE) ?: return@delete
                val tenantId = call.attributes[TenantIdAttr]
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invalid group ID", "")
                when (val result = roleGroupService.deleteGroup(groupId, tenantId)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            route("/members") {
                post("/{userId}") {
                    requireScope(call, ApiScope.GROUPS_WRITE) ?: return@post
                    val tenantId = call.attributes[TenantIdAttr]
                    val groupId =
                        call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                            ?: return@post call.respondProblem(
                                HttpStatusCode.BadRequest,
                                "Invalid group ID",
                                "",
                            )
                    val userId =
                        call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                            ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Invalid user ID", "")
                    roleGroupService.addUserToGroup(userId, groupId, tenantId)
                    call.respond(HttpStatusCode.NoContent, "")
                }

                delete("/{userId}") {
                    requireScope(call, ApiScope.GROUPS_WRITE) ?: return@delete
                    val tenantId = call.attributes[TenantIdAttr]
                    val groupId =
                        call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                            ?: return@delete call.respondProblem(
                                HttpStatusCode.BadRequest,
                                "Invalid group ID",
                                "",
                            )
                    val userId =
                        call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                            ?: return@delete call.respondProblem(
                                HttpStatusCode.BadRequest,
                                "Invalid user ID",
                                "",
                            )
                    roleGroupService.removeUserFromGroup(userId, groupId, tenantId)
                    call.respond(HttpStatusCode.NoContent, "")
                }
            }
        }
    }
}
