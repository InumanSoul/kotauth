package com.kauth.adapter.web.admin

import com.kauth.adapter.web.api.respondAdminError
import com.kauth.adapter.web.api.respondProblem
import com.kauth.adapter.web.api.toWorkspaceApiDto
import com.kauth.domain.model.ApiScope
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.WorkspaceCreationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `POST /admin/api/v1/tenants` — creates a workspace without the console, so a deployment can be
 * provisioned from a master bootstrap API key alone. Same creation path as the console, through
 * [WorkspaceCreationService]: the workspace answers discovery and issues tokens straight away.
 */
fun Route.adminTenantRoutes(
    apiKeyService: ApiKeyService,
    tenantRepository: TenantRepository,
    workspaceCreationService: WorkspaceCreationService,
) {
    authenticate("api-key") {
        route("/admin/api/v1") {
            post("/tenants") {
                val masterKey = call.requireMasterAdminKey(apiKeyService, tenantRepository) ?: return@post
                if (ApiScope.TENANTS_WRITE !in masterKey.scopes) {
                    return@post call.respondProblem(
                        HttpStatusCode.Forbidden,
                        "Insufficient scope",
                        "API key must hold the '${ApiScope.TENANTS_WRITE}' scope.",
                    )
                }

                val request =
                    try {
                        call.receive<CreateTenantRequest>()
                    } catch (e: Exception) {
                        call.application.log.debug("Create-tenant body rejected", e)
                        return@post call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid request body",
                            "Body must be a JSON object with 'slug' and 'displayName'.",
                        )
                    }

                val result =
                    workspaceCreationService.create(
                        slug = request.slug.trim().lowercase(),
                        displayName = request.displayName.trim(),
                        issuerUrl = request.issuerUrl?.trim()?.takeIf { it.isNotBlank() },
                        registrationEnabled = request.registrationEnabled,
                        emailVerificationRequired = request.emailVerificationRequired,
                    )
                when (result) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toWorkspaceApiDto())
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }
        }
    }
}

@Serializable
data class CreateTenantRequest(
    val slug: String,
    val displayName: String,
    val issuerUrl: String? = null,
    val registrationEnabled: Boolean = true,
    val emailVerificationRequired: Boolean = false,
)
