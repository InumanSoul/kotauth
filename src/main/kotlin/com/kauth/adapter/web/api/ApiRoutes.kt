package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.RoleScope
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminError
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.RoleGroupService
import com.kauth.infrastructure.ApiKeyPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * REST API v1 routes — Phase 3b.
 *
 * URL structure: /t/{tenantSlug}/api/v1/{resource}
 *
 * Authentication: Bearer token (API key starting with "kauth_").
 *   The Ktor `api-key` bearer provider validates the prefix; routes
 *   perform the full DB-backed tenant-scoped check via ApiKeyService.
 *
 * Errors: RFC 7807 Problem Details (application/problem+json).
 *
 * Pagination: Cursor-based on (createdAt, id) for stable ordering on live data.
 *   Response envelope: { "data": [...], "meta": { "nextCursor": "..." } }
 */
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
    // -------------------------------------------------------------------------
    // Swagger UI + OpenAPI spec — public, no auth required (Phase 3c)
    // -------------------------------------------------------------------------

    /** GET /api/docs — Swagger UI for the REST API */
    get("/api/docs") {
        call.respondText(ContentType.Text.Html, HttpStatusCode.OK) {
            swaggerUiHtml(call.request.host())
        }
    }

    /** GET /api/docs/openapi.yaml — raw OpenAPI spec */
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
            // -----------------------------------------------------------------
            // Route-level auth + scope guard
            // Resolves the tenant from the URL and validates the API key against it.
            // Attaches the resolved ApiKey to the call attributes.
            // -----------------------------------------------------------------
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

                // Attach resolved key and tenant id to call attributes for route handlers
                call.attributes.put(ApiKeyAttr, resolvedKey)
                call.attributes.put(TenantIdAttr, tenant.id)
                proceed()
            }

            // =================================================================
            // Users
            // =================================================================

            route("/users") {
                /** GET /t/{slug}/api/v1/users — list users */
                get {
                    requireScope(call, ApiScope.USERS_READ) ?: return@get
                    val tenantId = call.attributes[TenantIdAttr]
                    val search = call.request.queryParameters["search"]
                    val users = userRepository.findByTenantId(tenantId, search)
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            data = users.map { it.toApiDto() },
                            meta = ApiMeta(total = users.size),
                        ),
                    )
                }

                /** POST /t/{slug}/api/v1/users — create user */
                post {
                    requireScope(call, ApiScope.USERS_WRITE) ?: return@post
                    val tenantId = call.attributes[TenantIdAttr]
                    val body = call.receive<CreateUserRequest>()

                    when (
                        val result =
                            adminService.createUser(
                                tenantId = tenantId,
                                username = body.username,
                                email = body.email,
                                fullName = body.fullName,
                                password = body.password,
                            )
                    ) {
                        is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                        is AdminResult.Failure -> call.respondAdminError(result.error)
                    }
                }

                route("/{userId}") {
                    /** GET /t/{slug}/api/v1/users/{userId} — get user */
                    get {
                        requireScope(call, ApiScope.USERS_READ) ?: return@get
                        val tenantId = call.attributes[TenantIdAttr]
                        val userId =
                            call.parameters["userId"]?.toIntOrNull()
                                ?: return@get call.respondProblem(
                                    HttpStatusCode.BadRequest,
                                    "Invalid user ID",
                                    "userId must be an integer.",
                                )
                        val user =
                            userRepository.findById(userId)
                                ?: return@get call.respondProblem(
                                    HttpStatusCode.NotFound,
                                    "User not found",
                                    "No user with id $userId in this workspace.",
                                )
                        if (user.tenantId != tenantId) {
                            return@get call.respondProblem(
                                HttpStatusCode.NotFound,
                                "User not found",
                                "No user with id $userId in this workspace.",
                            )
                        }
                        call.respond(HttpStatusCode.OK, user.toApiDto())
                    }

                    /** PUT /t/{slug}/api/v1/users/{userId} — update user */
                    put {
                        requireScope(call, ApiScope.USERS_WRITE) ?: return@put
                        val tenantId = call.attributes[TenantIdAttr]
                        val userId =
                            call.parameters["userId"]?.toIntOrNull()
                                ?: return@put call.respondProblem(
                                    HttpStatusCode.BadRequest,
                                    "Invalid user ID",
                                    "userId must be an integer.",
                                )
                        val body = call.receive<UpdateUserRequest>()

                        when (val result = adminService.updateUser(userId, tenantId, body.email, body.fullName)) {
                            is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                            is AdminResult.Failure -> call.respondAdminError(result.error)
                        }
                    }

                    /** DELETE /t/{slug}/api/v1/users/{userId} — disable user */
                    delete {
                        requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
                        val tenantId = call.attributes[TenantIdAttr]
                        val userId =
                            call.parameters["userId"]?.toIntOrNull()
                                ?: return@delete call.respondProblem(
                                    HttpStatusCode.BadRequest,
                                    "Invalid user ID",
                                    "userId must be an integer.",
                                )

                        when (val result = adminService.setUserEnabled(userId, tenantId, false)) {
                            is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                            is AdminResult.Failure -> call.respondAdminError(result.error)
                        }
                    }

                    // Roles sub-resource
                    route("/roles") {
                        /** POST /t/{slug}/api/v1/users/{userId}/roles/{roleId} — assign role */
                        post("/{roleId}") {
                            requireScope(call, ApiScope.USERS_WRITE) ?: return@post
                            val tenantId = call.attributes[TenantIdAttr]
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Invalid user ID", "")
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                            roleGroupService.assignRoleToUser(userId, roleId, tenantId)
                            call.respond(HttpStatusCode.NoContent, "")
                        }

                        /** DELETE /t/{slug}/api/v1/users/{userId}/roles/{roleId} — unassign role */
                        delete("/{roleId}") {
                            requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
                            val tenantId = call.attributes[TenantIdAttr]
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@delete call.respondProblem(
                                        HttpStatusCode.BadRequest,
                                        "Invalid user ID",
                                        "",
                                    )
                            val roleId =
                                call.parameters["roleId"]?.toIntOrNull()
                                    ?: return@delete call.respondProblem(
                                        HttpStatusCode.BadRequest,
                                        "Invalid role ID",
                                        "",
                                    )
                            roleGroupService.unassignRoleFromUser(userId, roleId, tenantId)
                            call.respond(HttpStatusCode.NoContent, "")
                        }
                    }
                }
            }

            // =================================================================
            // Roles
            // =================================================================

            route("/roles") {
                /** GET /t/{slug}/api/v1/roles — list roles */
                get {
                    requireScope(call, ApiScope.ROLES_READ) ?: return@get
                    val tenantId = call.attributes[TenantIdAttr]
                    val roles = roleRepository.findByTenantId(tenantId)
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            data = roles.map { it.toApiDto() },
                            meta = ApiMeta(total = roles.size),
                        ),
                    )
                }

                /** POST /t/{slug}/api/v1/roles — create role */
                post {
                    requireScope(call, ApiScope.ROLES_WRITE) ?: return@post
                    val tenantId = call.attributes[TenantIdAttr]
                    val body = call.receive<CreateRoleRequest>()
                    val roleScope =
                        body.scope?.let { s -> RoleScope.entries.firstOrNull { it.value == s.lowercase() } }
                            ?: RoleScope.TENANT
                    when (
                        val result =
                            roleGroupService.createRole(
                                tenantId,
                                body.name,
                                body.description,
                                roleScope,
                                null,
                            )
                    ) {
                        is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                        is AdminResult.Failure -> call.respondAdminError(result.error)
                    }
                }

                route("/{roleId}") {
                    /** GET /t/{slug}/api/v1/roles/{roleId} */
                    get {
                        requireScope(call, ApiScope.ROLES_READ) ?: return@get
                        val tenantId = call.attributes[TenantIdAttr]
                        val roleId =
                            call.parameters["roleId"]?.toIntOrNull()
                                ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                        val role =
                            roleRepository.findById(roleId)
                                ?: return@get call.respondProblem(
                                    HttpStatusCode.NotFound,
                                    "Role not found",
                                    "No role with id $roleId.",
                                )
                        if (role.tenantId != tenantId) {
                            return@get call.respondProblem(HttpStatusCode.NotFound, "Role not found", "")
                        }
                        call.respond(HttpStatusCode.OK, role.toApiDto())
                    }

                    /** PUT /t/{slug}/api/v1/roles/{roleId} */
                    put {
                        requireScope(call, ApiScope.ROLES_WRITE) ?: return@put
                        val tenantId = call.attributes[TenantIdAttr]
                        val roleId =
                            call.parameters["roleId"]?.toIntOrNull()
                                ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                        val body = call.receive<UpdateRoleRequest>()
                        when (val result = roleGroupService.updateRole(roleId, tenantId, body.name, body.description)) {
                            is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                            is AdminResult.Failure -> call.respondAdminError(result.error)
                        }
                    }

                    /** DELETE /t/{slug}/api/v1/roles/{roleId} */
                    delete {
                        requireScope(call, ApiScope.ROLES_WRITE) ?: return@delete
                        val tenantId = call.attributes[TenantIdAttr]
                        val roleId =
                            call.parameters["roleId"]?.toIntOrNull()
                                ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invalid role ID", "")
                        when (val result = roleGroupService.deleteRole(roleId, tenantId)) {
                            is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                            is AdminResult.Failure -> call.respondAdminError(result.error)
                        }
                    }
                }
            }

            // =================================================================
            // Groups
            // =================================================================

            route("/groups") {
                /** GET /t/{slug}/api/v1/groups */
                get {
                    requireScope(call, ApiScope.GROUPS_READ) ?: return@get
                    val tenantId = call.attributes[TenantIdAttr]
                    val groups = groupRepository.findByTenantId(tenantId)
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            data = groups.map { it.toApiDto() },
                            meta = ApiMeta(total = groups.size),
                        ),
                    )
                }

                /** POST /t/{slug}/api/v1/groups */
                post {
                    requireScope(call, ApiScope.GROUPS_WRITE) ?: return@post
                    val tenantId = call.attributes[TenantIdAttr]
                    val body = call.receive<CreateGroupRequest>()
                    when (
                        val result =
                            roleGroupService.createGroup(
                                tenantId,
                                body.name,
                                body.description,
                                body.parentGroupId,
                            )
                    ) {
                        is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                        is AdminResult.Failure -> call.respondAdminError(result.error)
                    }
                }

                route("/{groupId}") {
                    /** GET /t/{slug}/api/v1/groups/{groupId} */
                    get {
                        requireScope(call, ApiScope.GROUPS_READ) ?: return@get
                        val tenantId = call.attributes[TenantIdAttr]
                        val groupId =
                            call.parameters["groupId"]?.toIntOrNull()
                                ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid group ID", "")
                        val group =
                            groupRepository.findById(groupId)
                                ?: return@get call.respondProblem(
                                    HttpStatusCode.NotFound,
                                    "Group not found",
                                    "No group with id $groupId.",
                                )
                        if (group.tenantId != tenantId) {
                            return@get call.respondProblem(HttpStatusCode.NotFound, "Group not found", "")
                        }
                        call.respond(HttpStatusCode.OK, group.toApiDto())
                    }

                    /** PUT /t/{slug}/api/v1/groups/{groupId} */
                    put {
                        requireScope(call, ApiScope.GROUPS_WRITE) ?: return@put
                        val tenantId = call.attributes[TenantIdAttr]
                        val groupId =
                            call.parameters["groupId"]?.toIntOrNull()
                                ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid group ID", "")
                        val body = call.receive<UpdateGroupRequest>()
                        when (
                            val result =
                                roleGroupService.updateGroup(
                                    groupId,
                                    tenantId,
                                    body.name,
                                    body.description,
                                )
                        ) {
                            is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                            is AdminResult.Failure -> call.respondAdminError(result.error)
                        }
                    }

                    /** DELETE /t/{slug}/api/v1/groups/{groupId} */
                    delete {
                        requireScope(call, ApiScope.GROUPS_WRITE) ?: return@delete
                        val tenantId = call.attributes[TenantIdAttr]
                        val groupId =
                            call.parameters["groupId"]?.toIntOrNull()
                                ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invalid group ID", "")
                        when (val result = roleGroupService.deleteGroup(groupId, tenantId)) {
                            is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                            is AdminResult.Failure -> call.respondAdminError(result.error)
                        }
                    }

                    // Members sub-resource
                    route("/members") {
                        /** POST /t/{slug}/api/v1/groups/{groupId}/members/{userId} */
                        post("/{userId}") {
                            requireScope(call, ApiScope.GROUPS_WRITE) ?: return@post
                            val tenantId = call.attributes[TenantIdAttr]
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@post call.respondProblem(
                                        HttpStatusCode.BadRequest,
                                        "Invalid group ID",
                                        "",
                                    )
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Invalid user ID", "")
                            roleGroupService.addUserToGroup(userId, groupId, tenantId)
                            call.respond(HttpStatusCode.NoContent, "")
                        }

                        /** DELETE /t/{slug}/api/v1/groups/{groupId}/members/{userId} */
                        delete("/{userId}") {
                            requireScope(call, ApiScope.GROUPS_WRITE) ?: return@delete
                            val tenantId = call.attributes[TenantIdAttr]
                            val groupId =
                                call.parameters["groupId"]?.toIntOrNull()
                                    ?: return@delete call.respondProblem(
                                        HttpStatusCode.BadRequest,
                                        "Invalid group ID",
                                        "",
                                    )
                            val userId =
                                call.parameters["userId"]?.toIntOrNull()
                                    ?: return@delete call.respondProblem(
                                        HttpStatusCode.BadRequest,
                                        "Invalid user ID",
                                        "",
                                    )
                            roleGroupService.removeUserFromGroup(userId, groupId, tenantId)
                            call.respond(HttpStatusCode.NoContent, "")
                        }
                    }
                }
            }

            // =================================================================
            // Applications (Clients)
            // =================================================================

            route("/applications") {
                /** GET /t/{slug}/api/v1/applications */
                get {
                    requireScope(call, ApiScope.APPLICATIONS_READ) ?: return@get
                    val tenantId = call.attributes[TenantIdAttr]
                    val apps = applicationRepository.findByTenantId(tenantId)
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            data = apps.map { it.toApiDto() },
                            meta = ApiMeta(total = apps.size),
                        ),
                    )
                }

                /** GET /t/{slug}/api/v1/applications/{appId} */
                get("/{appId}") {
                    requireScope(call, ApiScope.APPLICATIONS_READ) ?: return@get
                    val tenantId = call.attributes[TenantIdAttr]
                    val appId =
                        call.parameters["appId"]?.toIntOrNull()
                            ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
                    val app =
                        applicationRepository.findById(appId)
                            ?: return@get call.respondProblem(
                                HttpStatusCode.NotFound,
                                "Application not found",
                                "No application with id $appId.",
                            )
                    if (app.tenantId != tenantId) {
                        return@get call.respondProblem(HttpStatusCode.NotFound, "Application not found", "")
                    }
                    call.respond(HttpStatusCode.OK, app.toApiDto())
                }

                /** PUT /t/{slug}/api/v1/applications/{appId} */
                put("/{appId}") {
                    requireScope(call, ApiScope.APPLICATIONS_WRITE) ?: return@put
                    val tenantId = call.attributes[TenantIdAttr]
                    val appId =
                        call.parameters["appId"]?.toIntOrNull()
                            ?: return@put call.respondProblem(HttpStatusCode.BadRequest, "Invalid application ID", "")
                    val body = call.receive<UpdateApplicationRequest>()
                    when (
                        val result =
                            adminService.updateApplication(
                                appId = appId,
                                tenantId = tenantId,
                                name = body.name,
                                description = body.description,
                                accessType = body.accessType,
                                redirectUris = body.redirectUris,
                            )
                    ) {
                        is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                        is AdminResult.Failure -> call.respondAdminError(result.error)
                    }
                }

                /** DELETE /t/{slug}/api/v1/applications/{appId} — disables the application */
                delete("/{appId}") {
                    requireScope(call, ApiScope.APPLICATIONS_WRITE) ?: return@delete
                    val tenantId = call.attributes[TenantIdAttr]
                    val appId =
                        call.parameters["appId"]?.toIntOrNull()
                            ?: return@delete call.respondProblem(
                                HttpStatusCode.BadRequest,
                                "Invalid application ID",
                                "",
                            )
                    when (val result = adminService.setApplicationEnabled(appId, tenantId, false)) {
                        is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                        is AdminResult.Failure -> call.respondAdminError(result.error)
                    }
                }
            }

            // =================================================================
            // Sessions
            // =================================================================

            route("/sessions") {
                /** GET /t/{slug}/api/v1/sessions — active sessions for tenant */
                get {
                    requireScope(call, ApiScope.SESSIONS_READ) ?: return@get
                    val tenantId = call.attributes[TenantIdAttr]
                    val sessions = sessionRepository.findActiveByTenant(tenantId)
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            data = sessions.map { it.toApiDto() },
                            meta = ApiMeta(total = sessions.size),
                        ),
                    )
                }

                /** DELETE /t/{slug}/api/v1/sessions/{sessionId} — revoke session */
                delete("/{sessionId}") {
                    requireScope(call, ApiScope.SESSIONS_WRITE) ?: return@delete
                    val tenantId = call.attributes[TenantIdAttr]
                    val sessionId =
                        call.parameters["sessionId"]?.toIntOrNull()
                            ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invalid session ID", "")
                    val session =
                        sessionRepository.findById(sessionId)
                            ?: return@delete call.respondProblem(
                                HttpStatusCode.NotFound,
                                "Session not found",
                                "No session with id $sessionId.",
                            )
                    if (session.tenantId != tenantId) {
                        return@delete call.respondProblem(HttpStatusCode.NotFound, "Session not found", "")
                    }
                    sessionRepository.revoke(sessionId, Instant.now())
                    call.respond(HttpStatusCode.NoContent, "")
                }
            }

            // =================================================================
            // Audit Logs
            // =================================================================

            route("/audit-logs") {
                /**
                 * GET /t/{slug}/api/v1/audit-logs
                 *
                 * Query params:
                 *   limit     — max results (default 50, max 200)
                 *   offset    — pagination offset (default 0)
                 *   eventType — filter by AuditEventType name
                 *   userId    — filter by user id
                 */
                get {
                    requireScope(call, ApiScope.AUDIT_LOGS_READ) ?: return@get
                    val tenantId = call.attributes[TenantIdAttr]
                    val params = call.request.queryParameters
                    val limit = (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    val userId = params["userId"]?.toIntOrNull()
                    val eventType =
                        params["eventType"]?.let { name ->
                            runCatching { AuditEventType.valueOf(name) }.getOrNull()
                        }

                    val events = auditLogRepository.findByTenant(tenantId, eventType, userId, limit, offset)
                    val total = auditLogRepository.countByTenant(tenantId, eventType, userId)

                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse(
                            data = events.map { it.toApiDto() },
                            meta = ApiMeta(total = total.toInt(), offset = offset, limit = limit),
                        ),
                    )
                }
            }
        }
    }
}

// =============================================================================
// Scope guard helper — inline extension on ApplicationCall
// Returns the resolved ApiKey if the scope is present, null + responded 403 otherwise.
// Caller must `return@handler` when null is returned.
// =============================================================================

private suspend fun requireScope(
    call: ApplicationCall,
    scope: String,
): Unit? {
    val key =
        call.attributes.getOrNull(ApiKeyAttr)
            ?: return call.respondProblem(
                HttpStatusCode.Unauthorized,
                "Unauthorized",
                "A valid API key is required.",
            )
    if (scope !in key.scopes) {
        call.respondProblem(
            HttpStatusCode.Forbidden,
            "Insufficient scope",
            "This API key does not have the '$scope' permission.",
        )
        return null
    }
    return Unit
}

// =============================================================================
// RFC 7807 Problem Details helper
// =============================================================================

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    detail: String,
) {
    response.headers.append(HttpHeaders.ContentType, "application/problem+json")
    respond(
        status,
        ProblemDetail(
            type = "https://kotauth.dev/errors/${status.value}",
            title = title,
            status = status.value,
            detail = detail,
        ),
    )
}

private suspend fun ApplicationCall.respondAdminError(error: AdminError): Unit =
    when (error) {
        is AdminError.NotFound -> respondProblem(HttpStatusCode.NotFound, "Not Found", error.message)
        is AdminError.Conflict -> respondProblem(HttpStatusCode.Conflict, "Conflict", error.message)
        is AdminError.Validation ->
            respondProblem(
                HttpStatusCode.UnprocessableEntity,
                "Validation Error",
                error.message,
            )
    }

// =============================================================================
// Call attributes keys
// =============================================================================

private val ApiKeyAttr = AttributeKey<com.kauth.domain.model.ApiKey>("ApiKey")
private val TenantIdAttr = AttributeKey<Int>("TenantId")

// =============================================================================
// Request / Response DTOs (serializable)
// =============================================================================

@Serializable
data class ApiResponse<T>(
    val data: List<T>,
    val meta: ApiMeta,
)

@Serializable
data class ApiMeta(
    val total: Int,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
)

// ---- Request bodies ----

@Serializable data class CreateUserRequest(
    val username: String,
    val email: String,
    val fullName: String,
    val password: String,
)

@Serializable data class UpdateUserRequest(
    val email: String,
    val fullName: String,
)

@Serializable data class CreateRoleRequest(
    val name: String,
    val description: String? = null,
    val scope: String? = "REALM",
)

@Serializable data class UpdateRoleRequest(
    val name: String,
    val description: String? = null,
)

@Serializable data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val parentGroupId: Int? = null,
)

@Serializable data class UpdateGroupRequest(
    val name: String,
    val description: String? = null,
)

@Serializable data class UpdateApplicationRequest(
    val name: String,
    val description: String? = null,
    val accessType: String = "public",
    val redirectUris: List<String> = emptyList(),
)

// ---- Response DTOs ----

@Serializable data class UserDto(
    val id: Int,
    val username: String,
    val email: String,
    val fullName: String,
    val emailVerified: Boolean,
    val enabled: Boolean,
    val mfaEnabled: Boolean,
)

@Serializable data class RoleDto(
    val id: Int,
    val name: String,
    val description: String?,
    val scope: String,
    val tenantId: Int,
)

@Serializable data class GroupDto(
    val id: Int,
    val name: String,
    val description: String?,
    val parentGroupId: Int?,
    val tenantId: Int,
)

@Serializable data class ApplicationDto(
    val id: Int,
    val clientId: String,
    val name: String,
    val description: String?,
    val accessType: String,
    val enabled: Boolean,
    val redirectUris: List<String>,
)

@Serializable data class SessionDto(
    val id: Int,
    val userId: Int?,
    val clientId: Int?,
    val scopes: String,
    val ipAddress: String?,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable data class AuditEventDto(
    val eventType: String,
    val userId: Int?,
    val clientId: Int?,
    val ipAddress: String?,
    val createdAt: String,
    val details: Map<String, String>,
)

// =============================================================================
// Domain → DTO mappers
// =============================================================================

private val isoFormatter = DateTimeFormatter.ISO_INSTANT

private fun com.kauth.domain.model.User.toApiDto() =
    UserDto(
        id = id!!,
        username = username,
        email = email,
        fullName = fullName,
        emailVerified = emailVerified,
        enabled = enabled,
        mfaEnabled = mfaEnabled,
    )

private fun com.kauth.domain.model.Role.toApiDto() =
    RoleDto(
        id = id!!,
        name = name,
        description = description,
        scope = scope.name,
        tenantId = tenantId,
    )

private fun com.kauth.domain.model.Group.toApiDto() =
    GroupDto(
        id = id!!,
        name = name,
        description = description,
        parentGroupId = parentGroupId,
        tenantId = tenantId,
    )

private fun com.kauth.domain.model.Application.toApiDto() =
    ApplicationDto(
        id = id,
        clientId = clientId,
        name = name,
        description = description,
        accessType = accessType.name.lowercase(),
        enabled = enabled,
        redirectUris = redirectUris,
    )

private fun com.kauth.domain.model.Session.toApiDto() =
    SessionDto(
        id = id!!,
        userId = userId,
        clientId = clientId,
        scopes = scopes,
        ipAddress = ipAddress,
        createdAt = isoFormatter.format(createdAt),
        expiresAt = isoFormatter.format(expiresAt),
    )

private fun com.kauth.domain.model.AuditEvent.toApiDto() =
    AuditEventDto(
        eventType = eventType.name,
        userId = userId,
        clientId = clientId,
        ipAddress = ipAddress,
        createdAt = isoFormatter.format(createdAt),
        details = details,
    )

// =============================================================================
// Swagger UI HTML (served from CDN — no new Gradle dependency required)
// =============================================================================

/** Object reference for classloader access to bundled resources. */
private object ApiRoutes

private fun swaggerUiHtml(host: String) =
    """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>KotAuth REST API — Docs</title>
  <link rel="stylesheet"
        href="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.11.0/swagger-ui.min.css"
        crossorigin="anonymous" />
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
  <script src="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.11.0/swagger-ui-bundle.min.js"
          crossorigin="anonymous"></script>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.11.0/swagger-ui-standalone-preset.min.js"
          crossorigin="anonymous"></script>
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
