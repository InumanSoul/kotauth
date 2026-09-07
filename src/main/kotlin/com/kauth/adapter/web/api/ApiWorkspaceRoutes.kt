package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.port.TenantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

internal fun Route.apiWorkspaceRoutes(tenantRepository: TenantRepository) {
    route("/workspace") {
        get {
            requireScope(call, ApiScope.WORKSPACE_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val tenant =
                tenantRepository.findById(tenantId)
                    ?: return@get call.respondProblem(
                        HttpStatusCode.NotFound,
                        "Workspace not found",
                        "No workspace resolved for tenant id ${tenantId.value}.",
                    )
            call.respond(HttpStatusCode.OK, tenant.toWorkspaceApiDto())
        }
    }
}
