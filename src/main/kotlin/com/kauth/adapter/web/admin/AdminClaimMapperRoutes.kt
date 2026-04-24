package com.kauth.adapter.web.admin

import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.service.AttributeResult
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.adminClaimMapperRoutes(claimMapperService: CachingClaimMapperService) {
    // ── List ────────────────────────────────────────────────────────────
    get("/settings/claim-mappers") {
        val ctx = call.adminContext()
        val mappers = claimMapperService.list(ctx.workspace.id)
        val toastMsg =
            when (call.request.queryParameters["saved"]) {
                "created" -> "Claim mapper created."
                "updated" -> "Claim mapper updated."
                "deleted" -> "Claim mapper deleted."
                else -> null
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.claimMappersListPage(
                workspace = ctx.workspace,
                allWorkspaces = ctx.wsPairs,
                loggedInAs = ctx.session.username,
                mappers = mappers,
                toastMessage = toastMsg,
            ),
        )
    }

    // ── New mapper form ─────────────────────────────────────────────────
    get("/settings/claim-mappers/new") {
        val ctx = call.adminContext()
        val prefilledKey = call.request.queryParameters["attributeKey"]
        val prefill =
            if (prefilledKey != null) {
                TenantClaimMapper(
                    tenantId = ctx.workspace.id,
                    attributeKey = prefilledKey,
                    claimName = "",
                )
            } else {
                null
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.claimMapperFormPage(
                workspace = ctx.workspace,
                allWorkspaces = ctx.wsPairs,
                loggedInAs = ctx.session.username,
                prefill = prefill,
            ),
        )
    }

    // ── Edit mapper form ────────────────────────────────────────────────
    get("/settings/claim-mappers/{attributeKey}/edit") {
        val ctx = call.adminContext()
        val attributeKey =
            call.parameters["attributeKey"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
        val existing =
            claimMapperService.list(ctx.workspace.id).firstOrNull { it.attributeKey == attributeKey }
                ?: return@get call.respondRedirect(
                    "/admin/workspaces/${ctx.slug}/settings/claim-mappers",
                )
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.claimMapperFormPage(
                workspace = ctx.workspace,
                allWorkspaces = ctx.wsPairs,
                loggedInAs = ctx.session.username,
                prefill = existing,
            ),
        )
    }

    // ── Create (POST) ───────────────────────────────────────────────────
    post("/settings/claim-mappers") {
        val ctx = call.adminContext()
        val params = call.receiveParameters()
        val mapper = parseMapperForm(ctx.workspace.id, params)

        when (val result = claimMapperService.upsert(mapper)) {
            is AttributeResult.Success ->
                call.respondRedirect(
                    "/admin/workspaces/${ctx.slug}/settings/claim-mappers?saved=created",
                )
            else ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.claimMapperFormPage(
                        workspace = ctx.workspace,
                        allWorkspaces = ctx.wsPairs,
                        loggedInAs = ctx.session.username,
                        prefill = mapper,
                        error = errorMessage(result),
                    ),
                )
        }
    }

    // ── Update (POST) ───────────────────────────────────────────────────
    post("/settings/claim-mappers/{attributeKey}") {
        val ctx = call.adminContext()
        val attributeKey =
            call.parameters["attributeKey"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        val params = call.receiveParameters()
        val mapper = parseMapperForm(ctx.workspace.id, params).copy(attributeKey = attributeKey)

        when (val result = claimMapperService.upsert(mapper)) {
            is AttributeResult.Success ->
                call.respondRedirect(
                    "/admin/workspaces/${ctx.slug}/settings/claim-mappers?saved=updated",
                )
            else ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.claimMapperFormPage(
                        workspace = ctx.workspace,
                        allWorkspaces = ctx.wsPairs,
                        loggedInAs = ctx.session.username,
                        prefill = mapper,
                        error = errorMessage(result),
                    ),
                )
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────
    post("/settings/claim-mappers/{attributeKey}/delete") {
        val ctx = call.adminContext()
        val attributeKey =
            call.parameters["attributeKey"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        claimMapperService.delete(ctx.workspace.id, attributeKey)
        call.respondRedirect(
            "/admin/workspaces/${ctx.slug}/settings/claim-mappers?saved=deleted",
        )
    }
}

private fun parseMapperForm(
    tenantId: com.kauth.domain.model.TenantId,
    params: io.ktor.http.Parameters,
): TenantClaimMapper =
    TenantClaimMapper(
        tenantId = tenantId,
        attributeKey = params["attributeKey"]?.trim().orEmpty(),
        claimName = params["claimName"]?.trim().orEmpty(),
        includeInAccess = params["includeInAccess"] == "true",
        includeInId = params["includeInId"] == "true",
    )

private fun errorMessage(result: AttributeResult<*>): String =
    when (result) {
        is AttributeResult.Success<*> -> "Unexpected internal state."
        is AttributeResult.ValidationError -> result.reason
        is AttributeResult.NotFound -> "${result.resource} not found."
        is AttributeResult.ReservedClaimName ->
            "Claim name '${result.claimName}' is reserved by OIDC/KotAuth. " +
                "Use a custom prefix, e.g. 'custom:${result.claimName}'."
        is AttributeResult.DuplicateClaimName ->
            "Another attribute in this tenant already maps to claim name '${result.claimName}'."
        is AttributeResult.LimitReached ->
            "This tenant has reached the maximum of ${result.max} claim mappers."
    }
