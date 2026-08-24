package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.ApiScope
import com.kauth.domain.service.ApiKeyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

/**
 * Admin routes for the SCIM provisioning page.
 *
 * Read-only: everything an operator changes here (the key and its dialect) is posted to the API
 * key routes, so this page never becomes a second place that writes a key.
 */
fun Route.adminScimRoutes(
    apiKeyService: ApiKeyService?,
    baseUrl: String,
) {
    get("/provisioning") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val scimKeys =
            apiKeyService
                ?.listForTenant(workspace.id)
                ?.filter { ApiScope.SCIM in it.scopes }
                ?: emptyList()

        val toast =
            if (call.request.queryParameters["saved"] == "dialect") {
                EnglishStrings.SCIM_DIALECT_SAVED_TOAST
            } else {
                null
            }

        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.scimProvisioningPage(
                workspace = workspace,
                scimKeys = scimKeys,
                endpointUrl = scimEndpointUrl(baseUrl, workspace.slug),
                allWorkspaces = wsPairs,
                loggedInAs = session.username,
                toastMessage = toast,
            ),
        )
    }
}

/** Mirrors the `/t/{tenantSlug}/scim/v2` mount in `ApiRoutes` — an operator copies this verbatim. */
internal fun scimEndpointUrl(
    baseUrl: String,
    slug: String,
): String = "${baseUrl.trimEnd('/')}/t/$slug/scim/v2"
