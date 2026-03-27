package com.kauth.adapter.web.api

import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.RoleGroupService
import com.kauth.infrastructure.ApiKeyPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.apiRoutes(
    apiKeyService: ApiKeyService,
    tenantRepository: TenantRepository,
    userRepository: UserRepository,
    roleRepository: RoleRepository,
    groupRepository: GroupRepository,
    applicationRepository: ApplicationRepository,
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
    roleGroupService: RoleGroupService,
    adminService: AdminService,
) {
    get("/api/docs") {
        call.respondText(ContentType.Text.Html, HttpStatusCode.OK) {
            swaggerUiHtml()
        }
    }

    get("/api/docs/openapi.yaml") {
        val spec =
            ApiRoutes::class.java
                .getResourceAsStream("/openapi/v1.yaml")
                ?.bufferedReader()
                ?.readText()
                ?: return@get call.respond(HttpStatusCode.NotFound, "OpenAPI spec not found.")
        call.respondText(spec, ContentType.parse("application/yaml"), HttpStatusCode.OK)
    }

    authenticate("api-key") {
        route("/t/{tenantSlug}/api/v1") {
            intercept(ApplicationCallPipeline.Call) {
                val slug =
                    call.parameters["tenantSlug"]
                        ?: return@intercept call.respondProblem(
                            status = HttpStatusCode.BadRequest,
                            title = "Missing tenant slug",
                            detail = "The tenantSlug path parameter is required.",
                        )

                val tenant =
                    tenantRepository.findBySlug(slug)
                        ?: return@intercept call.respondProblem(
                            status = HttpStatusCode.NotFound,
                            title = "Tenant not found",
                            detail = "No workspace with slug '$slug' exists.",
                        )

                val principal =
                    call.principal<ApiKeyPrincipal>()
                        ?: return@intercept call.respondProblem(
                            status = HttpStatusCode.Unauthorized,
                            title = "Unauthorized",
                            detail = "A valid API key is required. Include it as: Authorization: Bearer kauth_...",
                        )

                val resolvedKey =
                    apiKeyService.validate(principal.rawToken, tenant.id)
                        ?: return@intercept call.respondProblem(
                            status = HttpStatusCode.Unauthorized,
                            title = "Invalid API key",
                            detail = "The provided API key is invalid, expired, or has been revoked.",
                        )

                call.attributes.put(ApiKeyAttr, resolvedKey)
                call.attributes.put(TenantIdAttr, tenant.id)
                proceed()
            }

            apiUserRoutes(userRepository, adminService, roleGroupService)
            apiRbacRoutes(roleRepository, groupRepository, roleGroupService)
            apiApplicationRoutes(applicationRepository, adminService)
            apiSessionAuditRoutes(sessionRepository, auditLogRepository)
        }
    }
}

private object ApiRoutes

private fun swaggerUiHtml() =
    """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>KotAuth REST API — Docs</title>
  <link rel="icon" href="/static/brand/kotauth-negative-icon.svg" type="image/svg+xml" />
  <link rel="stylesheet" href="/static/swagger/swagger-ui.min.css" />
  <style>
    body { margin: 0; background: #fafafa; font-family: 'Inter', system-ui, sans-serif; }
    .swagger-ui { font-family: 'Inter', system-ui, sans-serif; }
    #swagger-ui .topbar { background: #0C0C0E; }
    .swagger-ui .btn.authorize { background: #1FBCFF; color: #05080a; border-color: #1FBCFF; }
    .swagger-ui .btn.authorize:hover { background: #0AAEE8; border-color: #0AAEE8; }
    .swagger-ui .btn.authorize svg { fill: #05080a; }
  </style>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="/static/swagger/swagger-ui-bundle.min.js"></script>
  <script src="/static/swagger/swagger-ui-standalone-preset.min.js"></script>
  <script src="/static/swagger/swagger-init.js"></script>
</body>
</html>
    """.trimIndent()
