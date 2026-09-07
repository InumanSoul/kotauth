package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.port.WebAuthnCredentialRepository
import com.kauth.domain.service.WebAuthnResult
import com.kauth.domain.service.WebAuthnService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Passkey admin REST API — list a user's passkeys and revoke a single
 * credential by its primary key. Reuses `USERS_READ` / `USERS_WRITE` rather
 * than adding narrower `PASSKEYS_*` scopes: every operator that manages user
 * identity already has these, and passkeys are a per-user credential.
 */
internal fun Route.apiPasskeyRoutes(
    webAuthnService: WebAuthnService,
    webAuthnCredentialRepository: WebAuthnCredentialRepository,
) {
    route("/users/{userId}/passkeys") {
        get {
            requireScope(call, ApiScope.USERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val userId = call.parseUserIdOr { return@get } ?: return@get
            val credentials = webAuthnService.listForUser(userId, tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = credentials.map { it.toApiDto() },
                    meta = ApiMeta(total = credentials.size, offset = 0, limit = credentials.size),
                ),
            )
        }
    }

    route("/passkeys/{credentialPk}") {
        delete {
            requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val credentialPk =
                call.parameters["credentialPk"]?.toLongOrNull()
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid credential ID",
                        "credentialPk must be an integer.",
                    )
            // Look up the credential first: the DELETE service call needs userId. Both the
            // missing-row and cross-tenant-row cases below respond with the identical message
            // so that credential existence can't be enumerated across tenants.
            val credential =
                webAuthnCredentialRepository.findById(credentialPk)
                    ?: return@delete call.respondProblem(HttpStatusCode.NotFound, "Not Found", "Passkey not found.")
            if (credential.tenantId != tenantId) {
                return@delete call.respondProblem(HttpStatusCode.NotFound, "Not Found", "Passkey not found.")
            }
            when (val result = webAuthnService.revoke(credential.userId, credentialPk, tenantId)) {
                is WebAuthnResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                is WebAuthnResult.Failure -> call.respondWebAuthnError(result.error)
            }
        }
    }
}
