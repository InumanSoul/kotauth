package com.kauth.adapter.web.admin

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.CorsPort
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.ApplicationManagementService
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

fun Route.adminApplicationRoutes(
    applicationManagementService: ApplicationManagementService,
    applicationRepository: ApplicationRepository,
    roleGroupService: RoleGroupService,
    corsPort: CorsPort? = null,
    resourceServerService: com.kauth.domain.service.ResourceServerService? = null,
) {
    route("/applications") {
        get("/new") {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val wsPairs = call.attributes[WsPairsAttr]
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createApplicationPage(workspace, wsPairs, session.username),
            )
        }

        post {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val slug = workspace.slug
            val params = call.receiveParameters()
            val clientId = params["clientId"]?.trim()?.lowercase() ?: ""
            val name = params["name"]?.trim() ?: ""
            val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
            val accessType = params["accessType"]?.trim() ?: "public"
            val redirectUris =
                params["redirectUris"]
                    ?.lines()
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()
            val prefill =
                ApplicationPrefill(
                    clientId = clientId,
                    name = name,
                    description = desc ?: "",
                    accessType = accessType,
                    redirectUris = params["redirectUris"] ?: "",
                )
            when (
                val result =
                    applicationManagementService.createApplication(
                        tenantId = workspace.id,
                        clientId = clientId,
                        name = name,
                        description = desc,
                        accessType = accessType,
                        redirectUris = redirectUris,
                        // Grants default until the admin form exposes a selector.
                        grantTypes = GrantType.defaultsFor(AccessType.fromValue(accessType)),
                    )
            ) {
                is AdminResult.Failure -> {
                    val wsPairs = call.attributes[WsPairsAttr]
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createApplicationPage(
                            workspace,
                            wsPairs,
                            session.username,
                            error = result.error.message,
                            prefill = prefill,
                        ),
                    )
                }
                is AdminResult.Success -> {
                    call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                }
            }
        }

        route("/{clientId}") {
            get {
                val session = call.sessions.get<AdminSession>()!!
                val workspace = call.attributes[WorkspaceAttr]
                val clientId =
                    call.parameters["clientId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val app =
                    applicationRepository.findByClientId(workspace.id, clientId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                val wsPairs = call.attributes[WsPairsAttr]
                val allApps = applicationRepository.findByTenantId(workspace.id)
                val newSecret = FlashStore.take(call.request.queryParameters["flash"])

                val defaultRoles =
                    when (val r = roleGroupService.getClientDefaultRoles(workspace.id, app.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> emptyList()
                    }
                val defaultRoleIds = defaultRoles.mapNotNull { it.id }.toSet()
                // Only tenant-scoped roles and this app's own client-scoped roles are
                // eligible — see RoleGroupService.setClientDefaultRoles scope guard.
                val availableDefaultRoles =
                    roleGroupService
                        .listRoles(workspace.id)
                        .filter { role ->
                            role.id !in defaultRoleIds &&
                                (role.scope == RoleScope.TENANT || role.clientId == app.id)
                        }

                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.applicationDetailPage(
                        workspace,
                        app,
                        wsPairs,
                        allApps,
                        session.username,
                        newSecret,
                        defaultRoles,
                        availableDefaultRoles,
                    ),
                )
            }

            get("/edit") {
                val session = call.sessions.get<AdminSession>()!!
                val workspace = call.attributes[WorkspaceAttr]
                val clientId =
                    call.parameters["clientId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val app =
                    applicationRepository.findByClientId(workspace.id, clientId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                val wsPairs = call.attributes[WsPairsAttr]
                val allApps = applicationRepository.findByTenantId(workspace.id)
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.editApplicationPage(workspace, app, wsPairs, allApps, session.username),
                )
            }

            post("/edit") {
                val session = call.sessions.get<AdminSession>()!!
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val clientId =
                    call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val app =
                    applicationRepository.findByClientId(workspace.id, clientId)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                val params = call.receiveParameters()
                val name = params["name"]?.trim() ?: ""
                val desc = params["description"]?.trim()?.takeIf { it.isNotBlank() }
                val accessType = params["accessType"]?.trim() ?: app.accessType.value
                val redirectUris =
                    params["redirectUris"]
                        ?.lines()
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() } ?: emptyList()
                val launcherUrl = params["launcherUrl"]?.trim().orEmpty()
                val iconUrl = params["iconUrl"]?.trim().orEmpty()
                val launcherVisible = params["launcherVisible"] == "true"
                val launcherDisplayOrder =
                    params["launcherDisplayOrder"]?.trim()?.toIntOrNull()?.coerceIn(0, 9999) ?: 0
                val audience = params["audience"]?.trim().orEmpty()
                when (
                    val result =
                        applicationManagementService.updateApplication(
                            appId = app.id,
                            tenantId = workspace.id,
                            name = name,
                            description = desc,
                            accessType = accessType,
                            redirectUris = redirectUris,
                            launcherUrl = launcherUrl,
                            iconUrl = iconUrl,
                            launcherVisible = launcherVisible,
                            launcherDisplayOrder = launcherDisplayOrder,
                            audience = audience,
                        )
                ) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
                    is AdminResult.Failure -> {
                        val wsPairs = call.attributes[WsPairsAttr]
                        val allApps = applicationRepository.findByTenantId(workspace.id)
                        call.respondHtml(
                            HttpStatusCode.UnprocessableEntity,
                            AdminView.editApplicationPage(
                                workspace,
                                app,
                                wsPairs,
                                allApps,
                                session.username,
                                error = result.error.message,
                            ),
                        )
                    }
                }
            }

            post("/toggle") {
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val clientId =
                    call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val app =
                    applicationRepository.findByClientId(workspace.id, clientId)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                applicationManagementService.setApplicationEnabled(app.id, workspace.id, !app.enabled)
                call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
            }

            post("/regenerate-secret") {
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val clientId =
                    call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val app =
                    applicationRepository.findByClientId(workspace.id, clientId)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                when (val result = applicationManagementService.regenerateClientSecret(app.id, workspace.id)) {
                    is AdminResult.Success -> {
                        val flashToken = FlashStore.put(result.value)
                        call.respondRedirect(
                            "/admin/workspaces/$slug/applications/$clientId?flash=$flashToken",
                        )
                    }
                    is AdminResult.Failure ->
                        call.respond(HttpStatusCode.BadRequest, result.error.message)
                }
            }

            post("/default-roles") {
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val clientId =
                    call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val app =
                    applicationRepository.findByClientId(workspace.id, clientId)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                val roleId =
                    call.receiveParameters()["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                updateDefaultRoles(app.id, workspace.id, roleGroupService) { current ->
                    current + roleId
                }
                call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
            }

            post("/default-roles/remove") {
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val clientId =
                    call.parameters["clientId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val app =
                    applicationRepository.findByClientId(workspace.id, clientId)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                val roleId =
                    call.receiveParameters()["roleId"]?.toIntOrNull()?.let { RoleId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                updateDefaultRoles(app.id, workspace.id, roleGroupService) { current ->
                    current - roleId
                }
                call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
            }

            if (resourceServerService != null) {
                get("/authorized-apis") {
                    val session = call.sessions.get<AdminSession>()!!
                    val workspace = call.attributes[WorkspaceAttr]
                    val wsPairs = call.attributes[WsPairsAttr]
                    val clientId =
                        call.parameters["clientId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val app =
                        applicationRepository.findByClientId(workspace.id, clientId)
                            ?: return@get call.respond(HttpStatusCode.NotFound)
                    val allApps = applicationRepository.findByTenantId(workspace.id)
                    val all = resourceServerService.list(workspace.id)
                    val authorizedIds =
                        resourceServerService
                            .listAuthorized(app.id)
                            .mapNotNull { it.id?.value }
                            .toSet()
                    val toast =
                        if (call.request.queryParameters["saved"] == "ok") {
                            com.kauth.adapter.web.EnglishStrings.TOAST_AUTHORIZED_APIS_UPDATED
                        } else {
                            null
                        }
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.clientAuthorizedApisPage(
                            workspace = workspace,
                            allWorkspaces = wsPairs,
                            loggedInAs = session.username,
                            application = app,
                            allApps = allApps,
                            allResources = all,
                            authorizedIds = authorizedIds,
                            toastMessage = toast,
                        ),
                    )
                }

                post("/authorized-apis") {
                    val session = call.sessions.get<AdminSession>()!!
                    val workspace = call.attributes[WorkspaceAttr]
                    val wsPairs = call.attributes[WsPairsAttr]
                    val slug = workspace.slug
                    val clientId =
                        call.parameters["clientId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val app =
                        applicationRepository.findByClientId(workspace.id, clientId)
                            ?: return@post call.respond(HttpStatusCode.NotFound)
                    val params = call.receiveParameters()
                    val selectedIds =
                        params
                            .getAll("resource")
                            .orEmpty()
                            .mapNotNull { it.toIntOrNull() }
                            .map {
                                com.kauth.domain.model
                                    .ResourceServerId(it)
                            }
                    when (val result = resourceServerService.setAuthorized(app.id, selectedIds)) {
                        is com.kauth.domain.service.ResourceServerResult.Success ->
                            call.respondRedirect(
                                "/admin/workspaces/$slug/applications/$clientId/authorized-apis?saved=ok",
                            )
                        is com.kauth.domain.service.ResourceServerResult.Failure -> {
                            val all = resourceServerService.list(workspace.id)
                            val allApps = applicationRepository.findByTenantId(workspace.id)
                            val errorMessage =
                                when (result.error) {
                                    com.kauth.domain.service.ResourceServerError.CrossTenant ->
                                        "One of the selected APIs belongs to a different workspace."
                                    com.kauth.domain.service.ResourceServerError.NotFound ->
                                        "One of the selected APIs no longer exists. Reload and try again."
                                    else -> "Could not save authorized APIs."
                                }
                            call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.clientAuthorizedApisPage(
                                    workspace = workspace,
                                    allWorkspaces = wsPairs,
                                    loggedInAs = session.username,
                                    application = app,
                                    allApps = allApps,
                                    allResources = all,
                                    authorizedIds = selectedIds.map { it.value }.toSet(),
                                    error = errorMessage,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Read-modify-write of a client's default roles — the admin console mutates one at a time. */
private fun updateDefaultRoles(
    appId: ApplicationId,
    tenantId: com.kauth.domain.model.TenantId,
    roleGroupService: RoleGroupService,
    transform: (List<RoleId>) -> List<RoleId>,
) {
    val current =
        when (val r = roleGroupService.getClientDefaultRoles(tenantId, appId)) {
            is AdminResult.Success -> r.value.mapNotNull { it.id }
            is AdminResult.Failure -> return
        }
    roleGroupService.setClientDefaultRoles(tenantId, appId, transform(current).distinct())
}
