package com.kauth.adapter.web.api

import com.kauth.adapter.web.admin.resolvedBaseUrl
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.RoleGroupService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

internal fun Route.apiUserRoutes(
    accountService: AdminAccountService,
    adminUserService: com.kauth.domain.service.AdminUserService,
    roleGroupService: RoleGroupService,
    mfaService: com.kauth.domain.service.MfaService,
    sessionRepository: com.kauth.domain.port.SessionRepository,
) {
    route("/users") {
        get {
            requireScope(call, ApiScope.USERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val search = call.request.queryParameters["search"]
            val limit =
                call.request.queryParameters["limit"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, 200)
                    ?: 50
            val offset =
                call.request.queryParameters["offset"]
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0
            val users = adminUserService.listUsers(tenantId, search, limit, offset)
            val total = adminUserService.countUsers(tenantId, search).toInt()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse(
                    data = users.map { it.toApiDto() },
                    meta = ApiMeta(total = total, offset = offset, limit = limit),
                ),
            )
        }

        post {
            requireScope(call, ApiScope.USERS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<CreateUserRequest>()

            when (
                val result =
                    adminUserService.createUser(
                        tenantId = tenantId,
                        username = body.username ?: "",
                        email = body.email,
                        fullName = body.fullName,
                        password = body.password,
                    )
            ) {
                is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        /**
         * Invite a new user — no password required. User receives an email
         * with a 72-hour invite link and sets their own password on first login.
         * Tenant must have SMTP configured. The canonical way to onboard users
         * for third-party platforms integrating KotAuth as their auth provider.
         */
        post("/invite") {
            requireScope(call, ApiScope.USERS_WRITE) ?: return@post
            val tenantId = call.attributes[TenantIdAttr]
            val body = call.receive<InviteUserRequest>()
            val baseUrl = call.resolvedBaseUrl()

            when (
                val result =
                    adminUserService.createUser(
                        tenantId = tenantId,
                        username = body.username ?: "",
                        email = body.email,
                        fullName = body.fullName,
                        password = null,
                        sendInvite = true,
                        baseUrl = baseUrl,
                    )
            ) {
                is AdminResult.Success -> call.respond(HttpStatusCode.Created, result.value.toApiDto())
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        route("/{userId}") {
            get {
                requireScope(call, ApiScope.USERS_READ) ?: return@get
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@get } ?: return@get
                val user =
                    when (val r = adminUserService.getUser(userId, tenantId)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure ->
                            return@get call.respondProblem(
                                HttpStatusCode.NotFound,
                                "User not found",
                                "No user with id $userId in this workspace.",
                            )
                    }
                call.respond(HttpStatusCode.OK, user.toApiDto())
            }

            put {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@put
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@put } ?: return@put
                val body = call.receive<UpdateUserRequest>()

                when (
                    val result =
                        adminUserService.updateUser(userId, tenantId, body.email, body.fullName, body.username)
                ) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toApiDto())
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            delete {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@delete } ?: return@delete

                when (val result = adminUserService.setUserEnabled(userId, tenantId, false)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            /**
             * Admin-triggered password reset email. Generates a standard reset
             * token and sends it via the tenant's configured SMTP. Safe to call
             * on users with any status — idempotent.
             */
            post("/send-reset-email") {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@post
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@post } ?: return@post
                val baseUrl = call.resolvedBaseUrl()
                when (val result = accountService.sendPasswordResetEmail(userId, tenantId, baseUrl)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            /**
             * Generates a one-time temporary-password link for the user and
             * returns it in the response. The user is stamped with
             * CHANGE_PASSWORD required action — on next login the credentials
             * are accepted but they are routed to a forced-change-password
             * page. Link expires in 24 hours.
             *
             * Response body contains the raw link. Callers MUST treat it as a
             * secret — do not log, do not email, hand it to the user over a
             * trusted channel.
             */
            post("/temporary-password") {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@post
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@post } ?: return@post
                when (val result = accountService.setTemporaryPassword(userId, tenantId)) {
                    is AdminResult.Success -> {
                        val slug = call.parameters["tenantSlug"] ?: ""
                        val baseUrl = call.resolvedBaseUrl()
                        val changeUrl = "$baseUrl/t/$slug/change-password?token=${result.value}"
                        call.respond(
                            HttpStatusCode.Created,
                            TemporaryPasswordResponse(
                                changePasswordUrl = changeUrl,
                                expiresAt =
                                    isoFormatter.format(
                                        java.time.Instant
                                            .now()
                                            .plusSeconds(24 * 3600),
                                    ),
                            ),
                        )
                    }
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            /** Enable a previously disabled user. Idempotent. */
            post("/enable") {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@post
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@post } ?: return@post
                when (val result = adminUserService.setUserEnabled(userId, tenantId, true)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }

            /**
             * Reset MFA for the user — removes all TOTP enrollments and recovery
             * codes. Helpdesk-triggered when a user is locked out of their MFA app.
             * Idempotent: succeeds even if no MFA was enrolled.
             */
            delete("/mfa/reset") {
                requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@delete } ?: return@delete
                when (val existing = adminUserService.getUser(userId, tenantId)) {
                    is AdminResult.Failure -> {
                        call.respondAdminError(existing.error)
                        return@delete
                    }
                    is AdminResult.Success -> {
                        mfaService.disableMfa(userId, tenantId)
                        call.respond(HttpStatusCode.NoContent, "")
                    }
                }
            }

            /**
             * Revokes all active sessions for the user. Used by security-alert
             * response playbooks. Returns the count of sessions revoked.
             */
            post("/revoke-sessions") {
                requireScope(call, ApiScope.SESSIONS_WRITE) ?: return@post
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@post } ?: return@post
                val active = sessionRepository.findActiveByUser(tenantId, userId)
                sessionRepository.revokeAllForUser(tenantId, userId, java.time.Instant.now())
                call.respond(HttpStatusCode.OK, RevokeSessionsResponse(revoked = active.size))
            }

            /** List active sessions for the user. */
            get("/sessions") {
                requireScope(call, ApiScope.SESSIONS_READ) ?: return@get
                val tenantId = call.attributes[TenantIdAttr]
                val userId = call.parseUserIdOr { return@get } ?: return@get
                val sessions = sessionRepository.findActiveByUser(tenantId, userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse(
                        data = sessions.map { it.toApiDto() },
                        meta = ApiMeta(total = sessions.size, offset = 0, limit = sessions.size),
                    ),
                )
            }

            route("/roles") {
                // {roleRef} accepts either a numeric role id or a role name —
                // see RoleGroupService.resolveRole.
                post("/{roleRef}") {
                    requireScope(call, ApiScope.USERS_WRITE) ?: return@post
                    val tenantId = call.attributes[TenantIdAttr]
                    val userId =
                        call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                            ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Invalid user ID", "")
                    val roleRef =
                        call.parameters["roleRef"]
                            ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Missing role", "")
                    when (val resolved = roleGroupService.resolveRole(tenantId, roleRef)) {
                        is AdminResult.Success ->
                            when (
                                val assigned =
                                    roleGroupService.assignRoleToUser(userId, resolved.value.id!!, tenantId)
                            ) {
                                is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                                is AdminResult.Failure -> call.respondAdminError(assigned.error)
                            }
                        is AdminResult.Failure -> call.respondAdminError(resolved.error)
                    }
                }

                delete("/{roleRef}") {
                    requireScope(call, ApiScope.USERS_WRITE) ?: return@delete
                    val tenantId = call.attributes[TenantIdAttr]
                    val userId =
                        call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                            ?: return@delete call.respondProblem(
                                HttpStatusCode.BadRequest,
                                "Invalid user ID",
                                "",
                            )
                    val roleRef =
                        call.parameters["roleRef"]
                            ?: return@delete call.respondProblem(HttpStatusCode.BadRequest, "Missing role", "")
                    when (val resolved = roleGroupService.resolveRole(tenantId, roleRef)) {
                        is AdminResult.Success ->
                            when (
                                val removed =
                                    roleGroupService.unassignRoleFromUser(userId, resolved.value.id!!, tenantId)
                            ) {
                                is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                                is AdminResult.Failure -> call.respondAdminError(removed.error)
                            }
                        is AdminResult.Failure -> call.respondAdminError(resolved.error)
                    }
                }
            }
        }
    }
}
