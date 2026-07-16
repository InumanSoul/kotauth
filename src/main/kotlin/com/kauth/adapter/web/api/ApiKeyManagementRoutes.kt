package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant

internal fun Route.apiKeyManagementRoutes(apiKeyService: ApiKeyService) {
    route("/api-keys") {
        get {
            requireScope(call, ApiScope.API_KEYS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val keys = apiKeyService.listForTenant(tenantId)
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = keys.map { it.toApiDto() },
                    meta = ApiMeta(total = keys.size, offset = 0, limit = keys.size),
                ),
            )
        }

        post {
            requireScope(call, ApiScope.API_KEYS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateApiKeyRequest>()
            val expiresAt =
                body.expiresAt?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                        ?: return@post call.respondProblem(
                            HttpStatusCode.UnprocessableEntity,
                            "Invalid expiresAt",
                            "expiresAt must be an ISO-8601 instant (e.g. 2027-01-01T00:00:00Z).",
                        )
                }
            when (val result = apiKeyService.create(tenantId, body.name, body.scopes, expiresAt)) {
                is ApiKeyResult.Success ->
                    call.respond(
                        HttpStatusCode.Created,
                        CreateApiKeyResponse(
                            apiKey = result.value.apiKey.toApiDto(),
                            rawKey = result.value.rawKey,
                        ),
                    )
                is ApiKeyResult.Failure -> call.respondApiKeyError(result.error)
            }
        }

        delete("/{id}") {
            requireScope(call, ApiScope.API_KEYS_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid API key ID",
                        "id must be an integer.",
                    )
            when (val result = apiKeyService.revoke(id, tenantId)) {
                is ApiKeyResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                is ApiKeyResult.Failure -> call.respondApiKeyError(result.error)
            }
        }
    }
}
