package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.RoleGroupService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

fun Route.adminUserRoutes(
    adminService: AdminService,
    roleGroupService: RoleGroupService,
    sessionRepository: SessionRepository,
) {
    route("/users") {
        get {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val search =
                call.request.queryParameters["q"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            val pageSize = 25
            val totalCount = adminService.countUsers(workspace.id, search)
            val totalPages = ((totalCount + pageSize - 1) / pageSize).toInt().coerceAtLeast(1)
            val page =
                (
                    call.request.queryParameters["page"]
                        ?.toIntOrNull()
                        ?.coerceAtLeast(1) ?: 1
                ).coerceAtMost(totalPages)
            val offset = (page - 1) * pageSize
            val users = adminService.listUsers(workspace.id, search, limit = pageSize, offset = offset)
            val wsPairs = call.attributes[WsPairsAttr]
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.userListPage(
                    workspace,
                    users,
                    wsPairs,
                    session.username,
                    search,
                    page = page,
                    totalPages = totalPages,
                    totalCount = totalCount,
                ),
            )
        }

        get("/new") {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val wsPairs = call.attributes[WsPairsAttr]
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createUserPage(workspace, wsPairs, session.username),
            )
        }

        post {
            val session = call.sessions.get<AdminSession>()!!
            val workspace = call.attributes[WorkspaceAttr]
            val slug = workspace.slug
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val email = params["email"]?.trim() ?: ""
            val fullName = params["fullName"]?.trim() ?: ""
            val setupMode = params["setupMode"] ?: "password"
            val sendInvite = setupMode == "invite"
            val password = if (sendInvite) null else (params["password"] ?: "")
            val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
            when (
                val result =
                    adminService.createUser(workspace.id, username, email, fullName, password, sendInvite, baseUrl)
            ) {
                is AdminResult.Success ->
                    call.respondRedirect("/admin/workspaces/$slug/users/${result.value.id?.value}")
                is AdminResult.Failure -> {
                    val wsPairs = call.attributes[WsPairsAttr]
                    val prefill = UserPrefill(username = username, email = email, fullName = fullName)
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createUserPage(
                            workspace,
                            wsPairs,
                            session.username,
                            error = result.error.message,
                            prefill = prefill,
                        ),
                    )
                }
            }
        }

        route("/{userId}") {
            get {
                val session = call.sessions.get<AdminSession>()!!
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val user =
                    when (val r = adminService.getUser(userId, workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@get call.respond(HttpStatusCode.NotFound)
                    }
                val sessions = sessionRepository.findActiveByUser(workspace.id, userId)
                val wsPairs = call.attributes[WsPairsAttr]
                val userRoles = roleGroupService.getRolesForUser(userId)
                val userGroups = roleGroupService.getGroupsForUser(userId)
                val savedParam = call.request.queryParameters["saved"]
                val successMsg =
                    when (savedParam) {
                        "true" -> EnglishStrings.TOAST_PROFILE_SAVED
                        "reset_email_sent" -> EnglishStrings.TOAST_RESET_EMAIL_SENT
                        "unlocked" -> EnglishStrings.TOAST_UNLOCKED
                        "disabled" -> EnglishStrings.TOAST_USER_DISABLED
                        "enabled" -> EnglishStrings.TOAST_USER_ENABLED
                        "sessions_revoked" -> EnglishStrings.TOAST_USER_SESSIONS_REVOKED
                        "verification_sent" -> EnglishStrings.TOAST_VERIFICATION_SENT
                        "invite_sent" -> EnglishStrings.TOAST_INVITE_SENT
                        "invite_resent" -> EnglishStrings.TOAST_INVITE_RESENT
                        else -> null
                    }
                val errorParam =
                    when (savedParam) {
                        "reset_email_failed" -> "Failed to send password reset email. Check SMTP configuration."
                        "invite_send_failed" -> EnglishStrings.TOAST_INVITE_SEND_FAILED
                        else -> null
                    }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.userDetailPage(
                        workspace,
                        user,
                        sessions,
                        wsPairs,
                        session.username,
                        successMessage = successMsg,
                        editError = errorParam,
                        roles = userRoles,
                        groups = userGroups,
                    ),
                )
            }

            get("/profile-fragment") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val user =
                    when (val r = adminService.getUser(userId, workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@get call.respond(HttpStatusCode.NotFound)
                    }
                val userRoles = roleGroupService.getRolesForUser(userId)
                val userGroups = roleGroupService.getGroupsForUser(userId)
                call.respondText(
                    AdminView.userProfileReadFragment(
                        user,
                        roles = userRoles,
                        groups = userGroups,
                    ),
                    ContentType.Text.Html,
                )
            }

            get("/edit-fragment") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val user =
                    when (val r = adminService.getUser(userId, workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@get call.respond(HttpStatusCode.NotFound)
                    }
                call.respondText(
                    AdminView.userProfileEditFragment(workspace, user),
                    ContentType.Text.Html,
                )
            }

            post("/unlock") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                when (adminService.unlockUser(userId, workspace.id)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=unlocked")
                    is AdminResult.Failure ->
                        call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/toggle") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val user =
                    when (val r = adminService.getUser(userId, workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@post call.respond(HttpStatusCode.NotFound)
                    }
                val toastKey = if (user.enabled) "disabled" else "enabled"
                when (adminService.toggleUserEnabled(userId, workspace.id)) {
                    is AdminResult.Success ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=$toastKey",
                        )
                    is AdminResult.Failure ->
                        call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/edit") {
                val session = call.sessions.get<AdminSession>()!!
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val user =
                    when (val r = adminService.getUser(userId, workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@post call.respond(HttpStatusCode.NotFound)
                    }
                val params = call.receiveParameters()
                val email = params["email"]?.trim() ?: ""
                val fullName = params["fullName"]?.trim() ?: ""
                val isHtmx = call.request.headers["HX-Request"] == "true"
                when (val result = adminService.updateUser(userId, workspace.id, email, fullName)) {
                    is AdminResult.Success -> {
                        if (isHtmx) {
                            val updatedUser = result.value
                            val userRoles = roleGroupService.getRolesForUser(userId)
                            val userGroups = roleGroupService.getGroupsForUser(userId)
                            call.respondText(
                                AdminView.userProfileReadFragment(
                                    updatedUser,
                                    successMessage = "Profile saved.",
                                    roles = userRoles,
                                    groups = userGroups,
                                ),
                                ContentType.Text.Html,
                            )
                        } else {
                            call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=true")
                        }
                    }
                    is AdminResult.Failure -> {
                        if (isHtmx) {
                            call.respondText(
                                AdminView.userProfileEditFragment(
                                    workspace,
                                    user,
                                    editError = result.error.message,
                                ),
                                ContentType.Text.Html,
                                HttpStatusCode.UnprocessableEntity,
                            )
                        } else {
                            val sessions = sessionRepository.findActiveByUser(workspace.id, userId)
                            val wsPairs = call.attributes[WsPairsAttr]
                            val userRoles = roleGroupService.getRolesForUser(userId)
                            val userGroups = roleGroupService.getGroupsForUser(userId)
                            call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.userDetailPage(
                                    workspace,
                                    user,
                                    sessions,
                                    wsPairs,
                                    session.username,
                                    editError = result.error.message,
                                    roles = userRoles,
                                    groups = userGroups,
                                ),
                            )
                        }
                    }
                }
            }

            post("/revoke-sessions") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                sessionRepository.revokeAllForUser(workspace.id, userId)
                call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=sessions_revoked")
            }

            post("/send-verification") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                adminService.resendVerificationEmail(userId, workspace.id, baseUrl)
                call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=verification_sent")
            }

            post("/send-reset-email") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                when (adminService.sendPasswordResetEmail(userId, workspace.id, baseUrl)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=reset_email_sent")
                    is AdminResult.Failure ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=reset_email_failed",
                        )
                }
            }

            post("/resend-invite") {
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                when (adminService.resendInvite(userId, workspace.id, baseUrl)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=invite_resent")
                    is AdminResult.Failure ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=invite_send_failed",
                        )
                }
            }
        }
    }
}
