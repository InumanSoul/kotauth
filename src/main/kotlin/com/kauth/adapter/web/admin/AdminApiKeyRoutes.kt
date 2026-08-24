package com.kauth.adapter.web.admin

import com.kauth.adapter.web.scim.scimDialectFor
import com.kauth.domain.model.ApiScope
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

fun Route.adminApiKeyRoutes(apiKeyService: ApiKeyService?) {
    get("/settings/api-keys") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val keys = apiKeyService?.listForTenant(workspace.id) ?: emptyList()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.apiKeysListPage(workspace, keys, wsPairs, session.username),
        )
    }

    get("/settings/api-keys/new") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val preselected =
            call.request.queryParameters
                .getAll("scope")
                .orEmpty()
                .filter { it in ApiScope.ALL }
                .toSet()
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.createApiKeyPage(
                workspace,
                wsPairs,
                session.username,
                preselectedScopes = preselected,
            ),
        )
    }

    post("/settings/api-keys") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val svc = apiKeyService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)

        val params = call.receiveParameters()
        val name = params["name"]?.trim() ?: ""
        val scopes = params.getAll("scopes") ?: emptyList()
        // Resolving through the registry means an unknown id is stored as the RFC default rather
        // than as a value the SCIM surface would silently ignore later.
        val scimDialect = scimDialectFor(params["scimDialect"]).id
        val expiresAt =
            params["expiresAt"]?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    java.time.LocalDate
                        .parse(it)
                        .atStartOfDay(java.time.ZoneId.of("UTC"))
                        .toInstant()
                }.getOrNull()
            }

        when (val result = svc.create(workspace.id, name, scopes, expiresAt, scimDialect)) {
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
                        preselectedScopes = scopes.toSet(),
                        selectedDialect = scimDialect,
                    ),
                )
            }
        }
    }

    post("/settings/api-keys/{keyId}/revoke") {
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val keyId =
            call.parameters["keyId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val svc = apiKeyService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
        if (svc.findById(keyId, workspace.id)?.bootstrapName != null) {
            return@post call.respond(
                HttpStatusCode.Forbidden,
                "Bootstrapped keys can only be revoked via KAUTH_BOOTSTRAP_API_KEYS.",
            )
        }
        svc.revoke(keyId, workspace.id)
        call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
    }

    post("/settings/api-keys/{keyId}/delete") {
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val keyId =
            call.parameters["keyId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val svc = apiKeyService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
        if (svc.findById(keyId, workspace.id)?.bootstrapName != null) {
            return@post call.respond(
                HttpStatusCode.Forbidden,
                "Bootstrapped keys can only be deleted via KAUTH_BOOTSTRAP_API_KEYS.",
            )
        }
        svc.delete(keyId, workspace.id)
        call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
    }
}
