package com.kauth.adapter.web.admin

import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
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
    tenantRepository: TenantRepository,
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
) {
    route("/users") {
        get {
            val session = call.sessions.get<AdminSession>()!!
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val search =
                call.request.queryParameters["q"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            val users = userRepository.findByTenantId(workspace.id, search)
            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.userListPage(workspace, users, wsPairs, session.username, search),
            )
        }

        get("/new") {
            val session = call.sessions.get<AdminSession>()!!
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createUserPage(workspace, wsPairs, session.username),
            )
        }

        post {
            val session = call.sessions.get<AdminSession>()!!
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val workspace =
                tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val email = params["email"]?.trim() ?: ""
            val fullName = params["fullName"]?.trim() ?: ""
            val password = params["password"] ?: ""
            when (val result = adminService.createUser(workspace.id, username, email, fullName, password)) {
                is AdminResult.Success ->
                    call.respondRedirect("/admin/workspaces/$slug/users/${result.value.id}")
                is AdminResult.Failure -> {
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
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
                val slug =
                    call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                val user =
                    userRepository.findById(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
                if (user.tenantId != workspace.id) return@get call.respond(HttpStatusCode.NotFound)
                val sessions = sessionRepository.findActiveByUser(workspace.id, userId)
                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                val userRoles = roleGroupService.getRolesForUser(userId)
                val userGroups = roleGroupService.getGroupsForUser(userId)
                val savedParam = call.request.queryParameters["saved"]
                val errorParam = call.request.queryParameters["error"]
                val successMsg =
                    when (savedParam) {
                        "true" -> "Profile saved."
                        "reset_email_sent" -> "Password reset email sent successfully."
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
                val slug =
                    call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                val user =
                    userRepository.findById(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
                if (user.tenantId != workspace.id) return@get call.respond(HttpStatusCode.NotFound)
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
                val slug =
                    call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
                val user =
                    userRepository.findById(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
                if (user.tenantId != workspace.id) return@get call.respond(HttpStatusCode.NotFound)
                call.respondText(
                    AdminView.userProfileEditFragment(workspace, user),
                    ContentType.Text.Html,
                )
            }

            post("/toggle") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val user =
                    userRepository.findById(userId) ?: return@post call.respond(HttpStatusCode.NotFound)
                if (user.tenantId != workspace.id) return@post call.respond(HttpStatusCode.NotFound)
                adminService.setUserEnabled(userId, workspace.id, !user.enabled)
                call.respondRedirect("/admin/workspaces/$slug/users/$userId")
            }

            post("/edit") {
                val session = call.sessions.get<AdminSession>()!!
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val user =
                    userRepository.findById(userId) ?: return@post call.respond(HttpStatusCode.NotFound)
                if (user.tenantId != workspace.id) return@post call.respond(HttpStatusCode.NotFound)
                val params = call.receiveParameters()
                val email = params["email"]?.trim() ?: ""
                val fullName = params["fullName"]?.trim() ?: ""
                val isHtmx = call.request.headers["HX-Request"] == "true"
                when (val result = adminService.updateUser(userId, workspace.id, email, fullName)) {
                    is AdminResult.Success -> {
                        if (isHtmx) {
                            val updatedUser = userRepository.findById(userId) ?: user
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
                            call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=true")
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
                            val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
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
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                sessionRepository.revokeAllForUser(workspace.id, userId)
                call.respondRedirect("/admin/workspaces/$slug/users/$userId")
            }

            post("/send-verification") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                adminService.resendVerificationEmail(userId, workspace.id, baseUrl)
                call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=true")
            }

            post("/send-reset-email") {
                val slug =
                    call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId =
                    call.parameters["userId"]?.toIntOrNull()?.let { UserId(it) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace =
                    tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
                val baseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
                when (val result = adminService.sendPasswordResetEmail(userId, workspace.id, baseUrl)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/$userId?saved=reset_email_sent")
                    is AdminResult.Failure ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/$userId?error=${java.net.URLEncoder.encode(
                                result.error.message,
                                "UTF-8",
                            )}",
                        )
                }
            }
        }
    }
}
