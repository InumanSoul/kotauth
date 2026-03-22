package com.kauth.adapter.web.admin

import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.ApiKeyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

fun Route.adminApiKeyRoutes(
    tenantRepository: TenantRepository,
    apiKeyService: ApiKeyService?,
) {
    get("/settings/api-keys") {
        val session = call.sessions.get<AdminSession>()!!
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val workspace =
            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
        val keys = apiKeyService?.listForTenant(workspace.id) ?: emptyList()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.apiKeysListPage(workspace, keys, wsPairs, session.username),
        )
    }

    get("/settings/api-keys/new") {
        val session = call.sessions.get<AdminSession>()!!
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val workspace =
            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.createApiKeyPage(workspace, wsPairs, session.username),
        )
    }

    post("/settings/api-keys") {
        val session = call.sessions.get<AdminSession>()!!
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val workspace =
            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
        val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
        val svc = apiKeyService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)

        val params = call.receiveParameters()
        val name = params["name"]?.trim() ?: ""
        val scopes = params.getAll("scopes") ?: emptyList()
        val expiresAt =
            params["expiresAt"]?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    java.time.LocalDate
                        .parse(it)
                        .atStartOfDay(java.time.ZoneId.of("UTC"))
                        .toInstant()
                }.getOrNull()
            }

        when (val result = svc.create(workspace.id, name, scopes, expiresAt)) {
            is com.kauth.domain.service.ApiKeyResult.Success -> {
                val keys = svc.listForTenant(workspace.id)
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.apiKeysListPage(
                        workspace,
                        keys,
                        wsPairs,
                        session.username,
                        newKeyRaw = result.value.rawKey,
                    ),
                )
            }
            is com.kauth.domain.service.ApiKeyResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.createApiKeyPage(
                        workspace,
                        wsPairs,
                        session.username,
                        error = result.error.message,
                    ),
                )
            }
        }
    }

    post("/settings/api-keys/{keyId}/revoke") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val keyId =
            call.parameters["keyId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val workspace =
            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
        apiKeyService?.revoke(keyId, workspace.id)
        call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
    }

    post("/settings/api-keys/{keyId}/delete") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val keyId =
            call.parameters["keyId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val workspace =
            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
        apiKeyService?.delete(keyId, workspace.id)
        call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
    }
}
