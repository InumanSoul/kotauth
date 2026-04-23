package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AttributeResult
import com.kauth.domain.service.UserAttributeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * REST API — per-user custom attributes (key/value strings).
 *
 * Mounted under `/t/{tenantSlug}/api/v1/users/{userId}/attributes` inside the
 * authenticated/tenant-scoped route in [apiRoutes].
 *
 * Attributes are opaque to KotAuth: callers serialize structured data as strings.
 * Values are NOT validated for type. Attribute changes propagate to issued JWTs
 * on the next token issuance (worst case bounded by the claim-mapper cache TTL).
 */
internal fun Route.apiUserAttributeRoutes(userAttributeService: UserAttributeService) {
    route("/users/{userId}/attributes") {
        get {
            requireScope(call, ApiScope.USER_ATTRIBUTES_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val userId =
                call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                    ?: return@get call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid user ID",
                        "userId must be an integer.",
                    )

            when (val result = userAttributeService.list(userId, tenantId)) {
                is AttributeResult.Success -> call.respond(HttpStatusCode.OK, UserAttributesDto(result.value))
                else -> call.respondAttributeError(result)
            }
        }

        put("/{key}") {
            requireScope(call, ApiScope.USER_ATTRIBUTES_WRITE) ?: return@put
            val tenantId = call.attributes[TenantIdAttr]
            val userId =
                call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                    ?: return@put call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid user ID",
                        "userId must be an integer.",
                    )
            val key =
                call.parameters["key"]
                    ?: return@put call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Missing attribute key",
                        "The attribute key path parameter is required.",
                    )
            val body = call.receive<UpsertUserAttributeRequest>()

            when (val result = userAttributeService.upsert(userId, tenantId, key, body.value)) {
                is AttributeResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                else -> call.respondAttributeError(result)
            }
        }

        delete("/{key}") {
            requireScope(call, ApiScope.USER_ATTRIBUTES_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val userId =
                call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid user ID",
                        "userId must be an integer.",
                    )
            val key =
                call.parameters["key"]
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Missing attribute key",
                        "The attribute key path parameter is required.",
                    )

            when (val result = userAttributeService.delete(userId, tenantId, key)) {
                is AttributeResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                else -> call.respondAttributeError(result)
            }
        }
    }
}
