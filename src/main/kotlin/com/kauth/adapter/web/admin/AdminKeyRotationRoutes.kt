package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.UserId
import com.kauth.domain.port.TenantKeyRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.KeyRotationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.adminKeyRotationRoutes(
    keyRotationService: KeyRotationService,
    tenantKeyRepository: TenantKeyRepository,
) {
    get("/settings/signing-keys") {
        val ctx = call.adminContext()
        val keys = tenantKeyRepository.findEnabledKeys(ctx.workspace.id)
        val toastMsg =
            when (call.request.queryParameters["saved"]) {
                "rotated" -> EnglishStrings.TOAST_KEY_ROTATED
                "retired" -> EnglishStrings.TOAST_KEY_RETIRED
                else -> null
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.keyManagementPage(
                ctx.workspace,
                ctx.wsPairs,
                ctx.session.username,
                keys,
                toastMessage = toastMsg,
            ),
        )
    }

    post("/settings/signing-keys/rotate") {
        val ctx = call.adminContext()
        when (
            val result =
                keyRotationService.rotate(ctx.workspace.id, UserId(ctx.session.userId))
        ) {
            is AdminResult.Success ->
                call.respondRedirect(
                    "/admin/workspaces/${ctx.slug}/settings/signing-keys?saved=rotated",
                )
            is AdminResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.keyManagementPage(
                        ctx.workspace,
                        ctx.wsPairs,
                        ctx.session.username,
                        tenantKeyRepository.findEnabledKeys(ctx.workspace.id),
                        error = result.error.message,
                    ),
                )
        }
    }

    post("/settings/signing-keys/{keyId}/retire") {
        val ctx = call.adminContext()
        val keyId =
            call.parameters["keyId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        when (val result = keyRotationService.retireKey(ctx.workspace.id, keyId, UserId(ctx.session.userId))) {
            is AdminResult.Success ->
                call.respondRedirect(
                    "/admin/workspaces/${ctx.slug}/settings/signing-keys?saved=retired",
                )
            is AdminResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.keyManagementPage(
                        ctx.workspace,
                        ctx.wsPairs,
                        ctx.session.username,
                        tenantKeyRepository.findEnabledKeys(ctx.workspace.id),
                        error = result.error.message,
                    ),
                )
        }
    }
}
