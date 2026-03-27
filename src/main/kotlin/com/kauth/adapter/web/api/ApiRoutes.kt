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
  <link rel="stylesheet" href="/static/swagger/swagger-ui.min.css" />
  <style>
    body { margin: 0; background: #fafafa; font-family: system-ui, sans-serif; }
    #swagger-ui .topbar { background: #1a1a2e; }
    #swagger-ui .topbar-wrapper img { content: url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='white' width='28' height='28'><path d='M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z'/></svg>"); }
    #swagger-ui .topbar-wrapper a span { display: none; }
    #swagger-ui .topbar-wrapper::after {
      content: "KotAuth REST API";
      color: white;
      font-size: 1.125rem;
      font-weight: 600;
      margin-left: 0.75rem;
      align-self: center;
    }
  </style>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="/static/swagger/swagger-ui-bundle.min.js"></script>
  <script src="/static/swagger/swagger-ui-standalone-preset.min.js"></script>
  <script>
    SwaggerUIBundle({
      url: "/api/docs/openapi.yaml",
      dom_id: "#swagger-ui",
      deepLinking: true,
      presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
      layout: "StandaloneLayout",
      persistAuthorization: true,
      tryItOutEnabled: true,
      requestSnippetsEnabled: true,
      defaultModelsExpandDepth: 1,
      defaultModelExpandDepth: 3
    })
  </script>
</body>
</html>
    """.trimIndent()
