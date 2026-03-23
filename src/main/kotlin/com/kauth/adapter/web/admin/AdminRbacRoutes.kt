package com.kauth.adapter.web.admin

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.RoleGroupService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

fun Route.adminRbacRoutes(
    roleGroupService: RoleGroupService,
    tenantRepository: TenantRepository,
    applicationRepository: ApplicationRepository,
    userRepository: UserRepository,
) {
    // ===================================================================
    // Roles
    // ===================================================================

    route("/roles") {
        get {
            val session = call.sessions.get<AdminSession>()!!
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
            val roles = roleGroupService.listRoles(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.rolesListPage(workspace, roles, wsPairs, session.username),
            )
        }

        get("/create") {
            val session = call.sessions.get<AdminSession>()!!
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
            val apps = applicationRepository.findByTenantId(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createRolePage(workspace, apps, wsPairs, session.username),
            )
        }

        post {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val params = call.receiveParameters()
            val name = params["name"]?.trim() ?: ""
            val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
            val scopeStr = params["scope"]?.trim() ?: "tenant"
            val clientId = params["clientId"]?.toIntOrNull()?.let { ApplicationId(it) }

            when (
                val result =
                    roleGroupService.createRole(
                        tenantId = workspace.id,
                        name = name,
                        description = desc,
                        scope = RoleScope.fromValue(scopeStr),
                        clientId = clientId,
                    )
            ) {
                is AdminResult.Success ->
                    call.respondRedirect("/admin/workspaces/$slug/roles")
                is AdminResult.Failure -> {
                    val session = call.sessions.get<AdminSession>()!!
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val apps = applicationRepository.findByTenantId(workspace.id)
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createRolePage(
                            workspace,
                            apps,
                            wsPairs,
                            session.username,
                            error = result.error.message,
                        ),
                    )
                }
            }
        }

        route("/{roleId}") {
            get {
                val session = call.sessions.get<AdminSession>()!!
                val slug =
                    call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                val roles = roleGroupService.listRoles(workspace.id)
                val role =
                    roles.find { it.id == roleId } ?: return@get call.respond(HttpStatusCode.NotFound)
                val users = userRepository.findByTenantId(workspace.id, null)
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.roleDetailPage(workspace, role, roles, users, wsPairs, session.username),
                )
            }

            post("/edit") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val params = call.receiveParameters()
                roleGroupService.updateRole(
                    roleId,
                    workspace.id,
                    params["name"]?.trim() ?: "",
                    params["description"]?.trim()?.takeIf { it.isNotBlank() },
                )
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
            }

            post("/delete") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                roleGroupService.deleteRole(roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles")
            }

            post("/children") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val childId =
                    call.receiveParameters()["childRoleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.addChildRole(roleId, childId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
            }

            post("/remove-child") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val childId =
                    call.receiveParameters()["childRoleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.removeChildRole(roleId, childId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
            }

            post("/assign-user") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val userId =
                    call.receiveParameters()["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.assignRoleToUser(userId, roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
            }

            post("/unassign-user") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val roleId =
                    call.parameters["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val userId =
                    call.receiveParameters()["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.unassignRoleFromUser(userId, roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
            }
        }
    }

    // ===================================================================
    // Groups
    // ===================================================================

    route("/groups") {
        get {
            val session = call.sessions.get<AdminSession>()!!
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
            val groups = roleGroupService.listGroups(workspace.id)
            val roles = roleGroupService.listRoles(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.groupsListPage(workspace, groups, roles, wsPairs, session.username),
            )
        }

        get("/create") {
            val session = call.sessions.get<AdminSession>()!!
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
            val groups = roleGroupService.listGroups(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createGroupPage(workspace, groups, wsPairs, session.username),
            )
        }

        post {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val params = call.receiveParameters()
            val name = params["name"]?.trim() ?: ""
            val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
            val parentId = params["parentGroupId"]?.toIntOrNull()?.let { GroupId(it) }

            when (val result = roleGroupService.createGroup(workspace.id, name, desc, parentId)) {
                is AdminResult.Success ->
                    call.respondRedirect("/admin/workspaces/$slug/groups")
                is AdminResult.Failure -> {
                    val session = call.sessions.get<AdminSession>()!!
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    val groups = roleGroupService.listGroups(workspace.id)
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createGroupPage(
                            workspace,
                            groups,
                            wsPairs,
                            session.username,
                            error = result.error.message,
                        ),
                    )
                }
            }
        }

        route("/{groupId}") {
            get {
                val session = call.sessions.get<AdminSession>()!!
                val slug =
                    call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                val groups = roleGroupService.listGroups(workspace.id)
                val group =
                    groups.find { it.id == groupId } ?: return@get call.respond(HttpStatusCode.NotFound)
                val roles = roleGroupService.listRoles(workspace.id)
                val memberIds = roleGroupService.getUserIdsInGroup(groupId)
                val members = memberIds.mapNotNull { userRepository.findById(it) }
                val users = userRepository.findByTenantId(workspace.id, null)
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.groupDetailPage(
                        workspace,
                        group,
                        groups,
                        roles,
                        members,
                        users,
                        wsPairs,
                        session.username,
                    ),
                )
            }

            post("/edit") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val params = call.receiveParameters()
                roleGroupService.updateGroup(
                    groupId,
                    workspace.id,
                    name = params["name"]?.trim() ?: "",
                    description = params["description"]?.trim()?.takeIf { it.isNotBlank() },
                )
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
            }

            post("/delete") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                roleGroupService.deleteGroup(groupId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups")
            }

            post("/assign-role") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val roleId =
                    call.receiveParameters()["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.assignRoleToGroup(groupId, roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
            }

            post("/unassign-role") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val roleId =
                    call.receiveParameters()["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.unassignRoleFromGroup(groupId, roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
            }

            post("/add-member") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val userId =
                    call.receiveParameters()["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.addUserToGroup(userId, groupId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
            }

            post("/remove-member") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val groupId =
                    call.parameters["groupId"]?.toIntOrNull()?.let { GroupId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val userId =
                    call.receiveParameters()["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.removeUserFromGroup(userId, groupId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
            }
        }
    }
}
