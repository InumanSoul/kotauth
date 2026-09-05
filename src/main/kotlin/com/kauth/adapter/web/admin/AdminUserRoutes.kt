package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AttributeResult
import com.kauth.domain.service.ImpersonationService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.UserAttributeService
import com.kauth.domain.service.WebAuthnError
import com.kauth.domain.service.WebAuthnResult
import com.kauth.domain.service.WebAuthnService
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
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

/**
 * Whether the broker created this account on a first sign-in.
 *
 * The provisioning event is the only durable record of it: a just-in-time user carries no
 * `externalId`, and the `social_accounts` link it does have is one a locally registered user
 * acquires too the first time it signs in through a provider.
 */
private fun AuditLogRepository?.recordedJitProvisioning(
    tenantId: TenantId,
    userId: UserId,
): Boolean =
    this
        ?.findByTenant(tenantId, AuditEventType.JIT_USER_PROVISIONED, userId = userId, limit = 1)
        ?.isNotEmpty() ?: false

fun Route.adminUserRoutes(
    accountService: AdminAccountService,
    adminUserService: com.kauth.domain.service.AdminUserService,
    roleGroupService: RoleGroupService,
    sessionRepository: SessionRepository,
    userAttributeService: UserAttributeService,
    claimMapperService: CachingClaimMapperService,
    impersonationService: ImpersonationService? = null,
    userRepository: UserRepository? = null,
    auditLogRepository: AuditLogRepository? = null,
    webAuthnService: WebAuthnService? = null,
    mfaService: MfaService? = null,
    auditLogPort: AuditLogPort? = null,
    socialAccountRepository: com.kauth.domain.port.SocialAccountRepository? = null,
) {
    route("/users") {
        get {
            val ctx = call.adminContext()
            val search =
                call.request.queryParameters["q"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            val pageSize = DEFAULT_USER_PAGE_SIZE
            val totalCount = adminUserService.countUsers(ctx.workspace.id, search)
            val totalPages = ((totalCount + pageSize - 1) / pageSize).toInt().coerceAtLeast(1)
            val page =
                (
                    call.request.queryParameters["page"]
                        ?.toIntOrNull()
                        ?.coerceAtLeast(1) ?: 1
                ).coerceAtMost(totalPages)
            val offset = (page - 1) * pageSize
            val users = adminUserService.listUsers(ctx.workspace.id, search, limit = pageSize, offset = offset)
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.userListPage(
                    ctx.workspace,
                    users,
                    ctx.wsPairs,
                    ctx.session.username,
                    search,
                    page = page,
                    totalPages = totalPages,
                    totalCount = totalCount,
                    pageSize = pageSize,
                ),
            )
        }

        get("/new") {
            val ctx = call.adminContext()
            call.respondHtml(
                HttpStatusCode.OK,
                AdminView.createUserPage(ctx.workspace, ctx.wsPairs, ctx.session.username),
            )
        }

        post {
            val ctx = call.adminContext()
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val email = params["email"]?.trim() ?: ""
            val fullName = params["fullName"]?.trim() ?: ""
            // A cleared field has to reach the column as null: SCIM reads absent and empty alike,
            // so an empty string here would round-trip back out as a real name part.
            val givenName = params["givenName"]?.trim()?.ifBlank { null }
            val familyName = params["familyName"]?.trim()?.ifBlank { null }
            val setupMode = params["setupMode"] ?: "password"
            val sendInvite = setupMode == "invite"
            val password = if (sendInvite) null else (params["password"] ?: "")
            val baseUrl = call.resolvedBaseUrl()
            when (
                val result =
                    adminUserService.createUser(
                        ctx.workspace.id,
                        username,
                        email,
                        fullName,
                        password,
                        sendInvite,
                        baseUrl,
                        givenName = givenName,
                        familyName = familyName,
                    )
            ) {
                is AdminResult.Success ->
                    call.respondRedirect("/admin/workspaces/${ctx.slug}/users/${result.value.id?.value}")
                is AdminResult.Failure -> {
                    val prefill =
                        UserPrefill(
                            username = username,
                            email = email,
                            fullName = fullName,
                            givenName = givenName ?: "",
                            familyName = familyName ?: "",
                        )
                    call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createUserPage(
                            ctx.workspace,
                            ctx.wsPairs,
                            ctx.session.username,
                            error = result.error.message,
                            prefill = prefill,
                        ),
                    )
                }
            }
        }

        route("/{userId}") {
            get {
                val ctx = call.adminContext()
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val user =
                    when (val r = adminUserService.getUser(userId, ctx.workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@get call.respond(HttpStatusCode.NotFound)
                    }
                val sessions = sessionRepository.findActiveByUser(ctx.workspace.id, userId)
                val userRoles = roleGroupService.getRolesForUser(userId)
                val userGroups = roleGroupService.getGroupsForUser(userId)
                val attributes =
                    (userAttributeService.list(userId, ctx.workspace.id) as? AttributeResult.Success)
                        ?.value
                        ?: emptyMap()
                val mappedKeys =
                    claimMapperService
                        .list(ctx.workspace.id)
                        .associate { it.attributeKey to it.claimName }
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
                        "attribute_saved" -> "Attribute saved."
                        "attribute_deleted" -> "Attribute deleted."
                        "impersonation_ended" -> "Impersonation session ended."
                        "passkey_revoked" -> EnglishStrings.TOAST_PASSKEY_REVOKED
                        "passkeys_reset" -> EnglishStrings.TOAST_PASSKEYS_RESET
                        "mfa_reset" -> EnglishStrings.TOAST_MFA_RESET
                        else -> null
                    }
                val errorParam =
                    when (savedParam) {
                        "reset_email_failed" -> "Failed to send password reset email. Check SMTP configuration."
                        "verification_failed" -> "Failed to send verification email. Check SMTP configuration."
                        "invite_send_failed" -> EnglishStrings.TOAST_INVITE_SEND_FAILED
                        "temp_password_failed" -> "Could not generate a temporary password link. Try again."
                        "impersonation_disabled" -> EnglishStrings.IMPERSONATE_FAILED_DISABLED
                        "impersonation_locked" -> EnglishStrings.IMPERSONATE_FAILED_LOCKED
                        "impersonation_not_found" -> EnglishStrings.IMPERSONATE_FAILED_GENERIC
                        "impersonation_failed" -> EnglishStrings.IMPERSONATE_FAILED_GENERIC
                        else -> null
                    }
                val tempPasswordLink =
                    if (savedParam == "temp_password_set") {
                        FlashStore.take(call.request.queryParameters["flash"])
                    } else {
                        null
                    }
                val recentImpersonations =
                    auditLogRepository?.let { repo ->
                        repo
                            .findByTenant(
                                ctx.workspace.id,
                                AuditEventType.ADMIN_IMPERSONATION_STARTED,
                                limit = 200,
                            ).filter { it.details["target_user_id"] == userId.value.toString() }
                            .take(5)
                            .map {
                                ImpersonationRecord(
                                    adminUsername = it.details["admin_username"].orEmpty().ifBlank { "—" },
                                    startedAt = it.createdAt,
                                )
                            }
                    } ?: emptyList()
                val passkeys =
                    webAuthnService?.listForUser(userId, ctx.workspace.id) ?: emptyList()
                val brokeredOrigin = auditLogRepository.recordedJitProvisioning(ctx.workspace.id, userId)
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.userDetailPage(
                        ctx.workspace,
                        user,
                        sessions,
                        ctx.wsPairs,
                        ctx.session.username,
                        successMessage = successMsg,
                        editError = errorParam,
                        roles = userRoles,
                        groups = userGroups,
                        userAttributes = attributes,
                        mappedKeys = mappedKeys,
                        tempPasswordLink = tempPasswordLink,
                        recentImpersonations = recentImpersonations,
                        passkeys = passkeys,
                        brokeredOrigin = brokeredOrigin,
                        linkedIdentities = socialAccountRepository?.findByUserId(userId).orEmpty(),
                        activeImpersonation = call.activeImpersonation(sessionRepository, userRepository),
                    ),
                )
            }

            get("/profile-fragment") {
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val user =
                    when (val r = adminUserService.getUser(userId, workspace.id)) {
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
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val user =
                    when (val r = adminUserService.getUser(userId, workspace.id)) {
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
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                when (accountService.unlockUser(userId, workspace.id)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=unlocked")
                    is AdminResult.Failure ->
                        call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/toggle") {
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val user =
                    when (val r = adminUserService.getUser(userId, workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@post call.respond(HttpStatusCode.NotFound)
                    }
                val toastKey = if (user.enabled) "disabled" else "enabled"
                when (adminUserService.toggleUserEnabled(userId, workspace.id)) {
                    is AdminResult.Success ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=$toastKey",
                        )
                    is AdminResult.Failure ->
                        call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/edit") {
                val ctx = call.adminContext()
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val user =
                    when (val r = adminUserService.getUser(userId, ctx.workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@post call.respond(HttpStatusCode.NotFound)
                    }
                val params = call.receiveParameters()
                val email = params["email"]?.trim() ?: ""
                val fullName = params["fullName"]?.trim() ?: ""
                val username = params["username"]?.trim()
                val givenName = params["givenName"]?.trim()?.ifBlank { null }
                val familyName = params["familyName"]?.trim()?.ifBlank { null }
                val isHtmx = call.request.headers["HX-Request"] == "true"
                // The form submits the whole mutable profile, so a name part left empty means
                // "clear it" — which replaceUserProfile's other parameters treat as authoritative.
                // Username is the one exception: it keeps updateUser's null-means-unchanged
                // convention, but everything (including the rename) still lands in this single
                // write, so a mid-submission failure can never leave the username changed while
                // the rest of the profile — or the form the admin sees next — is not.
                val result =
                    adminUserService.replaceUserProfile(
                        userId = userId,
                        tenantId = ctx.workspace.id,
                        email = email,
                        fullName = fullName,
                        externalId = user.externalId,
                        givenName = givenName,
                        familyName = familyName,
                        username = username,
                    )
                when (result) {
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
                            call.respondRedirect("/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=true")
                        }
                    }
                    is AdminResult.Failure -> {
                        if (isHtmx) {
                            call.respondText(
                                AdminView.userProfileEditFragment(
                                    ctx.workspace,
                                    user,
                                    editError = result.error.message,
                                    roles = roleGroupService.getRolesForUser(userId),
                                    groups = roleGroupService.getGroupsForUser(userId),
                                ),
                                ContentType.Text.Html,
                                HttpStatusCode.UnprocessableEntity,
                            )
                        } else {
                            val sessions = sessionRepository.findActiveByUser(ctx.workspace.id, userId)
                            val userRoles = roleGroupService.getRolesForUser(userId)
                            val userGroups = roleGroupService.getGroupsForUser(userId)
                            call.respondHtml(
                                HttpStatusCode.UnprocessableEntity,
                                AdminView.userDetailPage(
                                    ctx.workspace,
                                    user,
                                    sessions,
                                    ctx.wsPairs,
                                    ctx.session.username,
                                    editError = result.error.message,
                                    roles = userRoles,
                                    groups = userGroups,
                                    brokeredOrigin =
                                        auditLogRepository.recordedJitProvisioning(ctx.workspace.id, userId),
                                ),
                            )
                        }
                    }
                }
            }

            post("/revoke-sessions") {
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                sessionRepository.revokeAllForUser(workspace.id, userId)
                call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=sessions_revoked")
            }

            if (impersonationService != null && userRepository != null) {
                adminUserImpersonationRoute(
                    impersonationService = impersonationService,
                    sessionRepository = sessionRepository,
                    userRepository = userRepository,
                )
            }

            post("/send-verification") {
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val baseUrl = call.resolvedBaseUrl()
                when (accountService.resendVerificationEmail(userId, workspace.id, baseUrl)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=verification_sent")
                    is AdminResult.Failure ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=verification_failed",
                        )
                }
            }

            post("/send-reset-email") {
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val baseUrl = call.resolvedBaseUrl()
                when (accountService.sendPasswordResetEmail(userId, workspace.id, baseUrl)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=reset_email_sent")
                    is AdminResult.Failure ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=reset_email_failed",
                        )
                }
            }

            post("/set-temporary-password") {
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val baseUrl = call.resolvedBaseUrl()
                when (val result = accountService.setTemporaryPassword(userId, workspace.id)) {
                    is AdminResult.Success -> {
                        val changeUrl = "$baseUrl/t/$slug/change-password?token=${result.value}"
                        val flashToken = FlashStore.put(changeUrl)
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?" +
                                "saved=temp_password_set&flash=$flashToken",
                        )
                    }
                    is AdminResult.Failure ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=temp_password_failed",
                        )
                }
            }

            post("/resend-invite") {
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val workspace = call.attributes[WorkspaceAttr]
                val slug = workspace.slug
                val baseUrl = call.resolvedBaseUrl()
                when (adminUserService.resendInvite(userId, workspace.id, baseUrl)) {
                    is AdminResult.Success ->
                        call.respondRedirect("/admin/workspaces/$slug/users/${userId.value}?saved=invite_resent")
                    is AdminResult.Failure ->
                        call.respondRedirect(
                            "/admin/workspaces/$slug/users/${userId.value}?saved=invite_send_failed",
                        )
                }
            }

            // ── Attributes: new form ────────────────────────────────────
            get("/attributes/new") {
                val ctx = call.adminContext()
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val user =
                    when (val r = adminUserService.getUser(userId, ctx.workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@get call.respond(HttpStatusCode.NotFound)
                    }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.userAttributeFormPage(
                        workspace = ctx.workspace,
                        user = user,
                        allWorkspaces = ctx.wsPairs,
                        loggedInAs = ctx.session.username,
                    ),
                )
            }

            // ── Attributes: edit form ───────────────────────────────────
            get("/attributes/{attrKey}/edit") {
                val ctx = call.adminContext()
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val attrKey =
                    call.parameters["attrKey"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val user =
                    when (val r = adminUserService.getUser(userId, ctx.workspace.id)) {
                        is AdminResult.Success -> r.value
                        is AdminResult.Failure -> return@get call.respond(HttpStatusCode.NotFound)
                    }
                val existing =
                    (userAttributeService.list(userId, ctx.workspace.id) as? AttributeResult.Success)
                        ?.value
                        ?.get(attrKey)
                        ?: return@get call.respondRedirect(
                            "/admin/workspaces/${ctx.slug}/users/${userId.value}",
                        )
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.userAttributeFormPage(
                        workspace = ctx.workspace,
                        user = user,
                        allWorkspaces = ctx.wsPairs,
                        loggedInAs = ctx.session.username,
                        existingKey = attrKey,
                        prefillKey = attrKey,
                        prefillValue = existing,
                    ),
                )
            }

            // ── Attributes: create (POST) ───────────────────────────────
            post("/attributes") {
                val ctx = call.adminContext()
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val params = call.receiveParameters()
                val key = params["key"]?.trim().orEmpty()
                val value = params["value"] ?: ""

                when (val result = userAttributeService.upsert(userId, ctx.workspace.id, key, value)) {
                    is AttributeResult.Success ->
                        call.respondRedirect(
                            "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=attribute_saved",
                        )
                    else -> {
                        val user =
                            when (val r = adminUserService.getUser(userId, ctx.workspace.id)) {
                                is AdminResult.Success -> r.value
                                is AdminResult.Failure -> return@post call.respond(HttpStatusCode.NotFound)
                            }
                        call.respondHtml(
                            HttpStatusCode.UnprocessableEntity,
                            AdminView.userAttributeFormPage(
                                workspace = ctx.workspace,
                                user = user,
                                allWorkspaces = ctx.wsPairs,
                                loggedInAs = ctx.session.username,
                                prefillKey = key,
                                prefillValue = value,
                                error = attributeErrorMessage(result),
                            ),
                        )
                    }
                }
            }

            // ── Attributes: update (POST to existing key) ───────────────
            post("/attributes/{attrKey}") {
                val ctx = call.adminContext()
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val attrKey =
                    call.parameters["attrKey"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val params = call.receiveParameters()
                val value = params["value"] ?: ""

                when (val result = userAttributeService.upsert(userId, ctx.workspace.id, attrKey, value)) {
                    is AttributeResult.Success ->
                        call.respondRedirect(
                            "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=attribute_saved",
                        )
                    else -> {
                        val user =
                            when (val r = adminUserService.getUser(userId, ctx.workspace.id)) {
                                is AdminResult.Success -> r.value
                                is AdminResult.Failure -> return@post call.respond(HttpStatusCode.NotFound)
                            }
                        call.respondHtml(
                            HttpStatusCode.UnprocessableEntity,
                            AdminView.userAttributeFormPage(
                                workspace = ctx.workspace,
                                user = user,
                                allWorkspaces = ctx.wsPairs,
                                loggedInAs = ctx.session.username,
                                existingKey = attrKey,
                                prefillKey = attrKey,
                                prefillValue = value,
                                error = attributeErrorMessage(result),
                            ),
                        )
                    }
                }
            }

            // ── Attributes: delete ──────────────────────────────────────
            post("/attributes/{attrKey}/delete") {
                val ctx = call.adminContext()
                val userId =
                    call.parameters.typedId("userId", ::UserId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                val attrKey =
                    call.parameters["attrKey"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                userAttributeService.delete(userId, ctx.workspace.id, attrKey)
                call.respondRedirect(
                    "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=attribute_deleted",
                )
            }

            // ── Passkeys: revoke single credential ──────────────────────
            if (webAuthnService != null && auditLogPort != null) {
                post("/passkeys/{credId}/revoke") {
                    val ctx = call.adminContext()
                    val userId =
                        call.parameters.typedId("userId", ::UserId)
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val credId =
                        call.parameters["credId"]?.toLongOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val actorId = UserId(ctx.session.userId)
                    when (val revokeResult = webAuthnService.revoke(userId, credId, ctx.workspace.id)) {
                        is WebAuthnResult.Success -> {
                            auditLogPort.record(
                                AuditEvent(
                                    tenantId = ctx.workspace.id,
                                    userId = actorId,
                                    clientId = null,
                                    eventType = AuditEventType.PASSKEY_ADMIN_REVOKED,
                                    ipAddress = call.request.origin.remoteAddress,
                                    userAgent = null,
                                    details =
                                        mapOf(
                                            "targetUser" to userId.value.toString(),
                                            "credentialId" to credId.toString(),
                                        ),
                                ),
                            )
                            call.respondRedirect(
                                "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=passkey_revoked",
                            )
                        }
                        is WebAuthnResult.Failure ->
                            if (revokeResult.error is WebAuthnError.CannotRevokeLast) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to
                                            "Cannot revoke your last passkey on a passwordless tenant. " +
                                            "Enable password sign-in first, or add another passkey.",
                                    ),
                                )
                            } else {
                                call.respond(HttpStatusCode.NotFound)
                            }
                    }
                }

                // ── Passkeys: reset all ──────────────────────────────────
                post("/passkeys/reset-all") {
                    val ctx = call.adminContext()
                    val userId =
                        call.parameters.typedId("userId", ::UserId)
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val actorId = UserId(ctx.session.userId)
                    if (!ctx.workspace.securityConfig.passwordLoginEnabled && !ctx.workspace.isSmtpReady) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "OperatorLockoutBlocked"),
                        )
                    }
                    webAuthnService.adminResetAll(ctx.workspace.id, userId, actorId)
                    call.respondRedirect(
                        "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=passkeys_reset",
                    )
                }
            }

            // ── MFA: admin reset ─────────────────────────────────────────
            if (mfaService != null && auditLogPort != null) {
                post("/mfa/reset") {
                    val ctx = call.adminContext()
                    val userId =
                        call.parameters.typedId("userId", ::UserId)
                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val actorId = UserId(ctx.session.userId)
                    mfaService.disableMfa(
                        userId = userId,
                        tenantId = ctx.workspace.id,
                        ipAddress = call.request.origin.remoteAddress,
                    )
                    auditLogPort.record(
                        AuditEvent(
                            tenantId = ctx.workspace.id,
                            userId = actorId,
                            clientId = null,
                            eventType = AuditEventType.MFA_ADMIN_RESET,
                            ipAddress = call.request.origin.remoteAddress,
                            userAgent = null,
                            details = mapOf("targetUser" to userId.value.toString()),
                        ),
                    )
                    call.respondRedirect(
                        "/admin/workspaces/${ctx.slug}/users/${userId.value}?saved=mfa_reset",
                    )
                }
            }
        }
    }
}

private fun attributeErrorMessage(result: AttributeResult<*>): String =
    when (result) {
        is AttributeResult.Success<*> -> "Unexpected internal state."
        is AttributeResult.ValidationError -> result.reason
        is AttributeResult.NotFound -> "${result.resource} not found."
        is AttributeResult.ReservedClaimName ->
            "Reserved claim name '${result.claimName}'."
        is AttributeResult.DuplicateClaimName ->
            "Duplicate claim name '${result.claimName}'."
        is AttributeResult.LimitReached ->
            "Mapper limit of ${result.max} reached."
    }

/**
 * The impersonation this admin session currently holds, if any.
 *
 * Starting a second one revokes the first, so the page that offers the button needs to know
 * whether there is anything to revoke before it asks.
 */
private fun ApplicationCall.activeImpersonation(
    sessionRepository: SessionRepository,
    userRepository: UserRepository?,
): ActiveImpersonation? {
    val session = sessions.get<AdminSession>() ?: return null
    val adminSessionId = session.adminSessionId?.let(::SessionId) ?: return null
    val running = sessionRepository.findActiveByImpersonator(adminSessionId).firstOrNull() ?: return null
    val targetUserId = running.userId ?: return null
    val target = userRepository?.findById(targetUserId, running.tenantId)
    return ActiveImpersonation(
        targetUserId = targetUserId.value,
        targetUsername = target?.username ?: "#${targetUserId.value}",
        targetWorkspaceSlug = attributes[WorkspaceAttr].slug,
        sessionId = running.id?.value ?: return null,
        startedAt = running.createdAt,
    )
}
