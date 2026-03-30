package com.kauth.adapter.web.admin

import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminService
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
    adminService: AdminService,
    applicationRepository: ApplicationRepository,
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
            val error =
                when {
                    clientId.isBlank() -> "Client ID is required."
                    !clientId.matches(
                        Regex("[a-z0-9-]+"),
                    ) -> "Client ID may only contain lowercase letters, numbers, and hyphens."
                    applicationRepository.existsByClientId(
                        workspace.id,
                        clientId,
                    ) -> "Client ID '$clientId' already exists."
                    name.isBlank() -> "Name is required."
                    else -> null
                }
            if (error != null) {
                val wsPairs = call.attributes[WsPairsAttr]
                return@post call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.createApplicationPage(
                        workspace,
                        wsPairs,
                        session.username,
                        error = error,
                        prefill = prefill,
                    ),
                )
            }
            applicationRepository.create(workspace.id, clientId, name, desc, accessType, redirectUris)
            call.respondRedirect("/admin/workspaces/$slug/applications/$clientId")
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
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.applicationDetailPage(
                        workspace,
                        app,
                        wsPairs,
                        allApps,
                        session.username,
                        newSecret,
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
                when (
                    val result =
                        adminService.updateApplication(
                            app.id,
                            workspace.id,
                            name,
                            desc,
                            accessType,
                            redirectUris,
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
                adminService.setApplicationEnabled(app.id, workspace.id, !app.enabled)
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
                when (val result = adminService.regenerateClientSecret(app.id, workspace.id)) {
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
        }
    }
}
