package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.service.AttributeResult
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * REST API — tenant-level claim mappers that project user attributes into JWTs.
 *
 * Mounted under `/t/{tenantSlug}/api/v1/claim-mappers` inside the authenticated/
 * tenant-scoped route in [apiRoutes].
 *
 * Writes are serialized through the caching layer so the tenant's mapper cache
 * is invalidated immediately — the next token issuance uses the fresh list.
 */
internal fun Route.apiClaimMapperRoutes(claimMapperService: CachingClaimMapperService) {
    route("/claim-mappers") {
        get {
            requireScope(call, ApiScope.CLAIM_MAPPERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val mappers = claimMapperService.list(tenantId).map { it.toApiDto() }
            call.respond(HttpStatusCode.OK, ClaimMappersDto(mappers))
        }

        put("/{attributeKey}") {
            requireScope(call, ApiScope.CLAIM_MAPPERS_WRITE) ?: return@put
            val tenantId = call.attributes[TenantIdAttr]
            val attributeKey =
                call.parameters["attributeKey"]
                    ?: return@put call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Missing attribute key",
                        "The attributeKey path parameter is required.",
                    )
            val body = call.receive<UpsertClaimMapperRequest>()

            val mapper =
                TenantClaimMapper(
                    tenantId = tenantId,
                    attributeKey = attributeKey,
                    claimName = body.claimName,
                    includeInAccess = body.includeInAccess,
                    includeInId = body.includeInId,
                )

            when (val result = claimMapperService.upsert(mapper)) {
                is AttributeResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                else -> call.respondAttributeError(result)
            }
        }

        delete("/{attributeKey}") {
            requireScope(call, ApiScope.CLAIM_MAPPERS_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val attributeKey =
                call.parameters["attributeKey"]
                    ?: return@delete call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Missing attribute key",
                        "The attributeKey path parameter is required.",
                    )

            when (val result = claimMapperService.delete(tenantId, attributeKey)) {
                is AttributeResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                else -> call.respondAttributeError(result)
            }
        }
    }
}
