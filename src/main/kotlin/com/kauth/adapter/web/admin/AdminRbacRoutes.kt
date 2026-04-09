package com.kauth.adapter.web.admin

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.RoleGroupService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

fun Route.adminRbacRoutes(
    roleGroupService: RoleGroupService,
    applicationRepository: ApplicationRepository,
    userRepository: UserRepository,
) {
    // ===================================================================
    // Roles
    // ===================================================================

    route("/roles") {
        get {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val wsPairs = call.attributes[WsPairsAttr]
            val roles = roleGroupService.listRoles(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.rolesListPage(workspace, roles, wsPairs, session.username),
            )
        }

        get("/create") {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val wsPairs = call.attributes[WsPairsAttr]
            val apps = applicationRepository.findByTenantId(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createRolePage(workspace, apps, wsPairs, session.username),
            )
        }

        post {
            val workspace = call.attributes[WorkspaceAttr]
            val slug = workspace.slug
            val params = call.receiveParameters()
            val name = params["name"]?.trim() ?: ""
            val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
            val scopeStr = params["scope"]?.trim() ?: "tenant"
            val clientId = params.typedId("clientId", ::ApplicationId)

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
                    val wsPairs = call.attributes[WsPairsAttr]
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
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val wsPairs = call.attributes[WsPairsAttr]
                val roles = roleGroupService.listRoles(workspace.id)
                val role =
                    roles.find { it.id == roleId } ?: return@get call.respond(HttpStatusCode.NotFound)
                val assignedUsers = roleGroupService.getUsersForRole(roleId, workspace.id)
                val toastMsg =
                    when (call.request.queryParameters["saved"]) {
                        "assigned" -> "User assigned to role."
                        "unassigned" -> "User removed from role."
                        else -> null
                    }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.roleDetailPage(
                        workspace,
                        role,
                        roles,
                        assignedUsers,
                        wsPairs,
                        session.username,
                        toastMessage = toastMsg,
                    ),
                )
            }

            get("/search-users") {
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                respondUserSearch(
                    call,
                    workspace,
                    userRepository,
                    actionUrl = "/admin/workspaces/${workspace.slug}/roles/${roleId.value}/assign-user",
                )
            }

            post("/edit") {
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
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
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                roleGroupService.deleteRole(roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles")
            }

            post("/children") {
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val childId =
                    call.receiveParameters().typedId("childRoleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.addChildRole(roleId, childId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
            }

            post("/remove-child") {
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val childId =
                    call.receiveParameters().typedId("childRoleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.removeChildRole(roleId, childId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
            }

            post("/assign-user") {
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val userId =
                    call.receiveParameters().typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                when (roleGroupService.assignRoleToUser(userId, roleId, workspace.id)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}?saved=assigned")
                    is AdminResult.Failure ->
                        call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}")
                }
            }

            post("/unassign-user") {
                val roleId =
                    call.parameters.typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val userId =
                    call.receiveParameters().typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.unassignRoleFromUser(userId, roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/roles/${roleId.value}?saved=unassigned")
            }
        }
    }

    // ===================================================================
    // Groups
    // ===================================================================

    route("/groups") {
        get {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val wsPairs = call.attributes[WsPairsAttr]
            val groups = roleGroupService.listGroups(workspace.id)
            val roles = roleGroupService.listRoles(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.groupsListPage(workspace, groups, roles, wsPairs, session.username),
            )
        }

        get("/create") {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val wsPairs = call.attributes[WsPairsAttr]
            val groups = roleGroupService.listGroups(workspace.id)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createGroupPage(workspace, groups, wsPairs, session.username),
            )
        }

        post {
            val workspace = call.attributes[WorkspaceAttr]
            val slug = workspace.slug
            val params = call.receiveParameters()
            val name = params["name"]?.trim() ?: ""
            val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
            val parentId = params.typedId("parentGroupId", ::GroupId)

            when (val result = roleGroupService.createGroup(workspace.id, name, desc, parentId)) {
                is AdminResult.Success ->
                    call.respondRedirect("/admin/workspaces/$slug/groups")
                is AdminResult.Failure -> {
                    val session = call.sessions.get<AdminSession>()!!
                    val wsPairs = call.attributes[WsPairsAttr]
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
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val wsPairs = call.attributes[WsPairsAttr]
                val groups = roleGroupService.listGroups(workspace.id)
                val group =
                    groups.find { it.id == groupId } ?: return@get call.respond(HttpStatusCode.NotFound)
                val roles = roleGroupService.listRoles(workspace.id)
                val members = roleGroupService.getUsersInGroup(groupId, workspace.id)
                val toastMsg =
                    when (call.request.queryParameters["saved"]) {
                        "member_added" -> "Member added to group."
                        "member_removed" -> "Member removed from group."
                        else -> null
                    }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.groupDetailPage(
                        workspace,
                        group,
                        groups,
                        roles,
                        members,
                        wsPairs,
                        session.username,
                        toastMessage = toastMsg,
                    ),
                )
            }

            get("/search-users") {
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                respondUserSearch(
                    call,
                    workspace,
                    userRepository,
                    actionUrl = "/admin/workspaces/${workspace.slug}/groups/${groupId.value}/add-member",
                )
            }

            post("/edit") {
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
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
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                roleGroupService.deleteGroup(groupId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups")
            }

            post("/assign-role") {
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val roleId =
                    call.receiveParameters().typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.assignRoleToGroup(groupId, roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
            }

            post("/unassign-role") {
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val roleId =
                    call.receiveParameters().typedId("roleId", ::RoleId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.unassignRoleFromGroup(groupId, roleId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
            }

            post("/add-member") {
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val userId =
                    call.receiveParameters().typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                when (roleGroupService.addUserToGroup(userId, groupId, workspace.id)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}?saved=member_added")
                    is AdminResult.Failure ->
                        call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}")
                }
            }

            post("/remove-member") {
                val groupId =
                    call.parameters.typedId("groupId", ::GroupId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val userId =
                    call.receiveParameters().typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                roleGroupService.removeUserFromGroup(userId, groupId, workspace.id)
                call.respondRedirect("/admin/workspaces/$slug/groups/${groupId.value}?saved=member_removed")
            }
        }
    }
}

private const val MAX_SEARCH_QUERY_LENGTH = 100
private const val MAX_SEARCH_RESULTS = 20

private suspend fun respondUserSearch(
    call: ApplicationCall,
    workspace: Tenant,
    userRepository: UserRepository,
    actionUrl: String,
) {
    val q =
        call.request.queryParameters["q"]
            ?.trim()
            ?.take(MAX_SEARCH_QUERY_LENGTH)
            ?.takeIf { it.isNotEmpty() }
    if (q == null) {
        call.respondText("", ContentType.Text.Html)
        return
    }
    val excludeIds =
        call.request.queryParameters["exclude"]
            ?.split(",")
            ?.take(500)
            ?.mapNotNull { it.trim().toIntOrNull()?.let { id -> UserId(id) } }
            ?.toSet() ?: emptySet()
    val users =
        userRepository
            .findByTenantId(workspace.id, q)
            .filter { it.id !in excludeIds }
            .take(MAX_SEARCH_RESULTS)
    call.respondText(
        renderFragment {
            entityPickerResults(
                items =
                    users.mapNotNull { u ->
                        u.id?.let { uid -> uid.value.toString() to "${u.username} (${u.email})" }
                    },
                idField = "userId",
                actionUrl = actionUrl,
            )
        },
        ContentType.Text.Html,
    )
}
