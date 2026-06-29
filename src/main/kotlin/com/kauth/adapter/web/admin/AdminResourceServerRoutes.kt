package com.kauth.adapter.web.admin

import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.service.ResourceServerError
import com.kauth.domain.service.ResourceServerResult
import com.kauth.domain.service.ResourceServerService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.adminResourceServerRoutes(service: ResourceServerService) {
    get("/settings/apis") {
        val ctx = call.adminContext()
        val resources = service.list(ctx.workspace.id)
        val toast = call.request.queryParameters["saved"]?.let(::toastFor)
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.resourceServersListPage(
                workspace = ctx.workspace,
                allWorkspaces = ctx.wsPairs,
                loggedInAs = ctx.session.username,
                resources = resources,
                toastMessage = toast,
            ),
        )
    }

    get("/settings/apis/new") {
        val ctx = call.adminContext()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.resourceServerFormPage(
                workspace = ctx.workspace,
                allWorkspaces = ctx.wsPairs,
                loggedInAs = ctx.session.username,
                prefill = null,
            ),
        )
    }

    post("/settings/apis") {
        val ctx = call.adminContext()
        val params = call.receiveParameters()
        val identifier = params["identifier"].orEmpty()
        val name = params["name"].orEmpty()
        val description = params["description"]?.takeIf { it.isNotBlank() }
        val scopes =
            params["scopes"]
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()

        when (val result = service.create(ctx.workspace.id, identifier, name, description, scopes)) {
            is ResourceServerResult.Success ->
                call.respondRedirect("/admin/workspaces/${ctx.slug}/settings/apis?saved=created")
            is ResourceServerResult.Failure -> {
                val prefill =
                    com.kauth.domain.model.ResourceServer(
                        tenantId = ctx.workspace.id,
                        identifier = identifier,
                        name = name,
                        description = description,
                        scopes = scopes,
                    )
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.resourceServerFormPage(
                        workspace = ctx.workspace,
                        allWorkspaces = ctx.wsPairs,
                        loggedInAs = ctx.session.username,
                        prefill = prefill,
                        error = errorMessage(result.error),
                    ),
                )
            }
        }
    }

    get("/settings/apis/{id}/edit") {
        val ctx = call.adminContext()
        val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val rs =
            service.get(ctx.workspace.id, ResourceServerId(id))
                ?: return@get call.respondRedirect("/admin/workspaces/${ctx.slug}/settings/apis")
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.resourceServerFormPage(
                workspace = ctx.workspace,
                allWorkspaces = ctx.wsPairs,
                loggedInAs = ctx.session.username,
                prefill = rs,
            ),
        )
    }

    post("/settings/apis/{id}") {
        val ctx = call.adminContext()
        val id = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val params = call.receiveParameters()
        val name = params["name"].orEmpty()
        val description = params["description"]?.takeIf { it.isNotBlank() }
        val scopes =
            params["scopes"]
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()

        when (val result = service.update(ctx.workspace.id, ResourceServerId(id), name, description, scopes)) {
            is ResourceServerResult.Success ->
                call.respondRedirect("/admin/workspaces/${ctx.slug}/settings/apis?saved=updated")
            is ResourceServerResult.Failure -> {
                val current = service.get(ctx.workspace.id, ResourceServerId(id))
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.resourceServerFormPage(
                        workspace = ctx.workspace,
                        allWorkspaces = ctx.wsPairs,
                        loggedInAs = ctx.session.username,
                        prefill = current?.copy(name = name, description = description, scopes = scopes),
                        error = errorMessage(result.error),
                    ),
                )
            }
        }
    }

    post("/settings/apis/{id}/enable") {
        val ctx = call.adminContext()
        val id = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        when (service.setEnabled(ctx.workspace.id, ResourceServerId(id), true)) {
            is ResourceServerResult.Success ->
                call.respondRedirect("/admin/workspaces/${ctx.slug}/settings/apis?saved=enabled")
            is ResourceServerResult.Failure ->
                call.respond(HttpStatusCode.NotFound)
        }
    }

    post("/settings/apis/{id}/disable") {
        val ctx = call.adminContext()
        val id = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        when (service.setEnabled(ctx.workspace.id, ResourceServerId(id), false)) {
            is ResourceServerResult.Success ->
                call.respondRedirect("/admin/workspaces/${ctx.slug}/settings/apis?saved=disabled")
            is ResourceServerResult.Failure ->
                call.respond(HttpStatusCode.NotFound)
        }
    }

    post("/settings/apis/{id}/delete") {
        val ctx = call.adminContext()
        val id = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val rsId = ResourceServerId(id)
        val current = service.get(ctx.workspace.id, rsId)
        val params = call.receiveParameters()
        val confirm = params["confirmIdentifier"].orEmpty()
        if (current == null || confirm != current.identifier) {
            return@post call.respondRedirect("/admin/workspaces/${ctx.slug}/settings/apis")
        }
        service.delete(ctx.workspace.id, rsId)
        call.respondRedirect("/admin/workspaces/${ctx.slug}/settings/apis?saved=deleted")
    }
}

private fun toastFor(key: String): String? =
    when (key) {
        "created" -> com.kauth.adapter.web.EnglishStrings.TOAST_API_CREATED
        "updated" -> com.kauth.adapter.web.EnglishStrings.TOAST_API_UPDATED
        "enabled" -> com.kauth.adapter.web.EnglishStrings.TOAST_API_ENABLED
        "disabled" -> com.kauth.adapter.web.EnglishStrings.TOAST_API_DISABLED
        "deleted" -> com.kauth.adapter.web.EnglishStrings.TOAST_API_DELETED
        else -> null
    }

private fun errorMessage(error: ResourceServerError): String =
    when (error) {
        is ResourceServerError.InvalidIdentifier -> "Invalid audience: ${error.reason}"
        ResourceServerError.InvalidName -> "Name is required."
        ResourceServerError.IdentifierAlreadyExists -> "An API with that audience identifier already exists."
        ResourceServerError.NotFound -> "API not found."
        ResourceServerError.CrossTenant -> "Cross-tenant authorization is not allowed."
    }
