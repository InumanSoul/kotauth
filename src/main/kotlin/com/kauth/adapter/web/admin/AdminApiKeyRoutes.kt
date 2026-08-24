package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.scim.scimDialectFor
import com.kauth.adapter.web.scim.scimDialects
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
        // A submitted id outside the registry is refused, not resolved: the selector only offers
        // registered ids, so anything else is a stale or tampered form. Reads are the opposite
        // question and keep scimDialectFor's fallback.
        val submittedDialect = params["scimDialect"]?.takeIf { it.isNotBlank() }
        if (submittedDialect != null && scimDialects.none { it.id == submittedDialect }) {
            return@post call.respondHtml(
                HttpStatusCode.UnprocessableEntity,
                AdminView.createApiKeyPage(
                    workspace,
                    wsPairs,
                    session.username,
                    error = EnglishStrings.SCIM_DIALECT_UNKNOWN_REFUSAL,
                    preselectedScopes = scopes.toSet(),
                ),
            )
        }
        val scimDialect = scimDialectFor(submittedDialect).id
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

    // The dialect is the one field on a key an operator can correct in place: picking the wrong one
    // only shows up later as rejected payloads, and a new key would mean reconfiguring the connector.
    post("/settings/api-keys/{keyId}/scim-dialect") {
        val workspace = call.attributes[WorkspaceAttr]
        val slug = workspace.slug
        val keyId =
            call.parameters["keyId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val svc = apiKeyService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
        val key = svc.findById(keyId, workspace.id) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (key.bootstrapName != null) {
            return@post call.respond(
                HttpStatusCode.Forbidden,
                EnglishStrings.SCIM_DIALECT_ENV_MANAGED_REFUSAL,
            )
        }
        val submittedDialect = call.receiveParameters()["scimDialect"]?.takeIf { it.isNotBlank() }
        if (submittedDialect != null && scimDialects.none { it.id == submittedDialect }) {
            return@post call.respond(HttpStatusCode.BadRequest, EnglishStrings.SCIM_DIALECT_UNKNOWN_REFUSAL)
        }
        val dialect = scimDialectFor(submittedDialect).id
        // The lookup above makes a failure here rare — the key would have to vanish between the two
        // reads — but the saved toast is only honest when the write actually happened.
        when (val result = svc.updateScimDialect(keyId, workspace.id, dialect)) {
            is com.kauth.domain.service.ApiKeyResult.Success ->
                call.respondRedirect("/admin/workspaces/$slug/provisioning?saved=dialect")
            is com.kauth.domain.service.ApiKeyResult.Failure ->
                call.respond(HttpStatusCode.NotFound, result.error.message)
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
        when (val result = svc.revoke(keyId, workspace.id)) {
            is com.kauth.domain.service.ApiKeyResult.Success ->
                call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
            is com.kauth.domain.service.ApiKeyResult.Failure ->
                call.respond(HttpStatusCode.NotFound, result.error.message)
        }
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
        when (val result = svc.delete(keyId, workspace.id)) {
            is com.kauth.domain.service.ApiKeyResult.Success ->
                call.respondRedirect("/admin/workspaces/$slug/settings/api-keys")
            is com.kauth.domain.service.ApiKeyResult.Failure ->
                call.respond(HttpStatusCode.NotFound, result.error.message)
        }
    }
}
