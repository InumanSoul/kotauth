package com.kauth.adapter.web.api

import com.kauth.adapter.web.plugin.TenantCorsPlugin
import com.kauth.adapter.web.scim.ScimScopePlugin
import com.kauth.adapter.web.scim.scimDiscoveryRoutes
import com.kauth.adapter.web.scim.scimUserRoutes
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TransactionRunner
import com.kauth.domain.port.WebAuthnCredentialRepository
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.CorsService
import com.kauth.domain.service.EmailOtpService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.UserAttributeService
import com.kauth.domain.service.WebAuthnService
import com.kauth.domain.service.WebhookService
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.apiRoutes(
    apiKeyService: ApiKeyService,
    tenantRepository: TenantRepository,
    roleRepository: RoleRepository,
    groupRepository: GroupRepository,
    applicationRepository: ApplicationRepository,
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
    roleGroupService: RoleGroupService,
    accountService: AdminAccountService,
    adminUserService: com.kauth.domain.service.AdminUserService,
    mfaService: com.kauth.domain.service.MfaService,
    applicationManagementService: com.kauth.domain.service.ApplicationManagementService,
    userAttributeService: UserAttributeService,
    claimMapperService: CachingClaimMapperService,
    emailOtpService: EmailOtpService,
    otpEmailRateLimiter: RateLimiterPort,
    otpIpRateLimiter: RateLimiterPort,
    apiWriteRateLimiter: RateLimiterPort,
    apiReadRateLimiter: RateLimiterPort,
    webhookService: WebhookService,
    resourceServerService: ResourceServerService,
    webAuthnService: WebAuthnService,
    webAuthnCredentialRepository: WebAuthnCredentialRepository,
    corsService: CorsService? = null,
    // Only the SCIM /Users write path needs this, but it's required rather than defaulted to a
    // no-op — a future wiring regression should fail to compile, not silently drop the rollback
    // boundary.
    transactionRunner: TransactionRunner,
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

    // Resolves {tenantSlug} and the API key against it, then stamps ApiKeyAttr/TenantIdAttr.
    // Shared by the REST API and SCIM route trees below — both need the same tenant/key
    // resolution, and defining it once keeps that resolution identical for both.
    val apiContextPlugin =
        createRouteScopedPlugin("ApiContextPlugin") {
            on(AuthenticationChecked) { call ->
                val slug =
                    call.parameters["tenantSlug"]
                        ?: return@on call.respondProblem(
                            status = HttpStatusCode.BadRequest,
                            title = "Missing tenant slug",
                            detail = "The tenantSlug path parameter is required.",
                        )

                val tenant =
                    tenantRepository.findBySlug(slug)
                        ?: return@on call.respondProblem(
                            status = HttpStatusCode.NotFound,
                            title = "Tenant not found",
                            detail = "No workspace with slug '$slug' exists.",
                        )

                val principal =
                    call.principal<ApiKeyPrincipal>()
                        ?: return@on call.respondProblem(
                            status = HttpStatusCode.Unauthorized,
                            title = "Unauthorized",
                            detail =
                                "A valid API key is required. Include it as: Authorization: Bearer kauth_...",
                        )

                val resolvedKey =
                    apiKeyService.validate(principal.rawToken, tenant.id)
                        ?: return@on call.respondProblem(
                            status = HttpStatusCode.Unauthorized,
                            title = "Invalid API key",
                            detail = "The provided API key is invalid, expired, or has been revoked.",
                        )

                call.attributes.put(ApiKeyAttr, resolvedKey)
                call.attributes.put(TenantIdAttr, tenant.id)
            }
        }

    // Shared with the SCIM /Users write path below — both trees write through the same API
    // keys and must share the same abuse-prevention budget, not just the REST one.
    val writeRateLimitPlugin =
        createRouteScopedPlugin("ApiWriteRateLimitPlugin") {
            on(AuthenticationChecked) { call ->
                val method = call.request.httpMethod
                if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) {
                    return@on
                }
                val key = call.attributes.getOrNull(ApiKeyAttr) ?: return@on
                val slug = call.parameters["tenantSlug"] ?: return@on
                val bucketKey = "api_write:${key.keyPrefix}:$slug"
                if (!apiWriteRateLimiter.isAllowed(bucketKey)) {
                    call.respondRateLimited(retryAfterSeconds = apiWriteRateLimiter.windowSeconds)
                }
            }
        }

    // Reads were previously unthrottled entirely (the write limiter above returns early for
    // GET/HEAD/OPTIONS) — including SCIM list endpoints, which can scan a whole directory in
    // chunks. Uses its own bucket prefix ("api_read" vs "api_write") so exhausting one budget
    // never blocks the other.
    val readRateLimitPlugin =
        createRouteScopedPlugin("ApiReadRateLimitPlugin") {
            on(AuthenticationChecked) { call ->
                val method = call.request.httpMethod
                if (method != HttpMethod.Get && method != HttpMethod.Head && method != HttpMethod.Options) {
                    return@on
                }
                val key = call.attributes.getOrNull(ApiKeyAttr) ?: return@on
                val slug = call.parameters["tenantSlug"] ?: return@on
                val bucketKey = "api_read:${key.keyPrefix}:$slug"
                if (!apiReadRateLimiter.isAllowed(bucketKey)) {
                    call.respondReadRateLimited(retryAfterSeconds = apiReadRateLimiter.windowSeconds)
                }
            }
        }

    authenticate("api-key") {
        route("/t/{tenantSlug}/api/v1") {
            if (corsService != null) {
                install(TenantCorsPlugin) {
                    this.corsService = corsService
                    tenantSlugParam = "tenantSlug"
                }
            }

            install(apiContextPlugin)
            install(writeRateLimitPlugin)
            install(readRateLimitPlugin)

            apiUserRoutes(accountService, adminUserService, roleGroupService, mfaService, sessionRepository)
            apiRbacRoutes(roleRepository, groupRepository, roleGroupService)
            apiWorkspaceRoutes(tenantRepository)
            apiWebhookRoutes(webhookService)
            apiResourceServerRoutes(resourceServerService, applicationRepository)
            apiApplicationRoutes(applicationRepository, applicationManagementService, roleGroupService)
            apiSessionAuditRoutes(sessionRepository, auditLogRepository)
            apiUserAttributeRoutes(userAttributeService)
            apiClaimMapperRoutes(claimMapperService)
            apiOtpRoutes(emailOtpService, otpEmailRateLimiter, otpIpRateLimiter)
            apiKeyManagementRoutes(apiKeyService)
            apiPasskeyRoutes(webAuthnService, webAuthnCredentialRepository)
        }

        route("/t/{tenantSlug}/scim/v2") {
            install(apiContextPlugin)
            install(ScimScopePlugin)
            install(writeRateLimitPlugin)
            install(readRateLimitPlugin)
            scimDiscoveryRoutes()
            scimUserRoutes(adminUserService, groupRepository, transactionRunner)
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
