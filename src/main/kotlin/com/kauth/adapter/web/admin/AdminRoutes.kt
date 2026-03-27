package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogRepository
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.MfaRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.AuthResult
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.domain.service.WebhookService
import com.kauth.infrastructure.AdminClientProvisioning
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.PortalClientProvisioning
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

fun Route.adminRoutes(
    authService: AuthService,
    adminService: AdminService,
    roleGroupService: RoleGroupService,
    appInfo: AppInfo,
    tenantRepository: TenantRepository,
    applicationRepository: ApplicationRepository,
    userRepository: UserRepository,
    sessionRepository: SessionRepository,
    auditLogRepository: AuditLogRepository,
    keyProvisioningService: KeyProvisioningService,
    mfaRepository: MfaRepository? = null,
    portalClientProvisioning: PortalClientProvisioning? = null,
    identityProviderRepository: IdentityProviderRepository? = null,
    apiKeyService: ApiKeyService? = null,
    webhookService: WebhookService? = null,
    encryptionService: EncryptionService,
    oauthService: OAuthService? = null,
    selfServiceService: UserSelfServiceService? = null,
    roleRepository: RoleRepository? = null,
    baseUrl: String = "",
    adminBypass: Boolean = false,
) {
    AdminView.setShellAppInfo(appInfo)

    route("/admin") {
        // ---------------------------------------------------------------
        // Login — OAuth PKCE flow (default) or break-glass bypass
        // ---------------------------------------------------------------

        get("/login") {
            if (call.sessions.get<AdminSession>() != null) {
                call.respondRedirect("/admin")
                return@get
            }
            if (adminBypass) {
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.loginPage(bypassNotice = "OAuth login is bypassed — direct credential login is active."),
                )
                return@get
            }
            // OAuth PKCE redirect to master tenant auth endpoint
            val verifier = generatePkceVerifier()
            val challenge = generatePkceChallenge(verifier)
            val state = generatePkceVerifier()
            val cookieVal = encryptionService.signCookie("$verifier|${System.currentTimeMillis()}|$state")
            call.response.cookies.append(
                name = "KOTAUTH_ADMIN_PKCE",
                value = cookieVal,
                maxAge = 300L,
                httpOnly = true,
                secure = baseUrl.startsWith("https"),
                path = "/admin",
            )
            val redirectUri = "$baseUrl/admin/callback"
            val authUrl =
                buildString {
                    append("/t/master/protocol/openid-connect/auth")
                    append("?response_type=code")
                    append("&client_id=").append(AdminClientProvisioning.ADMIN_CLIENT_ID)
                    append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
                    append("&scope=openid+profile+email")
                    append("&code_challenge=").append(challenge)
                    append("&code_challenge_method=S256")
                    append("&state=").append(state)
                }
            call.respondRedirect(authUrl)
        }

        post("/login") {
            if (!adminBypass) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val params = call.receiveParameters()
            val username = params["username"]?.trim() ?: ""
            val password = params["password"] ?: ""
            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]
            when (val result = authService.authenticate(Tenant.MASTER_SLUG, username, password, ipAddress, userAgent)) {
                is AuthResult.Success -> {
                    val user = result.value
                    // Verify admin role even in bypass mode
                    val bypassRoles = roleRepository?.findRolesForUser(user.id!!) ?: emptyList()
                    val master = tenantRepository.findBySlug(Tenant.MASTER_SLUG)
                    if (master != null && bypassRoles.none { it.name == "admin" && it.tenantId == master.id }) {
                        call.respondHtml(
                            HttpStatusCode.Forbidden,
                            AdminView.loginPage(error = "Your account does not have admin console access."),
                        )
                        return@post
                    }
                    call.sessions.set(
                        AdminSession(
                            userId = user.id?.value ?: 0,
                            tenantId = user.tenantId.value,
                            username = user.username,
                        ),
                    )
                    call.respondRedirect("/admin")
                }
                is AuthResult.Failure ->
                    call.respondHtml(
                        HttpStatusCode.Unauthorized,
                        AdminView.loginPage(error = "Invalid credentials."),
                    )
            }
        }

        // ---------------------------------------------------------------
        // OAuth callback — exchanges authorization code for session
        // ---------------------------------------------------------------

        get("/callback") {
            val rawPkce = call.request.cookies["KOTAUTH_ADMIN_PKCE"]
            if (rawPkce.isNullOrBlank()) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AdminView.errorPage("Session expired. Please try again.", "/admin/login"),
                )
                return@get
            }
            val pkcePayload = encryptionService.verifyCookie(rawPkce)
            if (pkcePayload == null) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AdminView.errorPage("Invalid session. Please try again.", "/admin/login"),
                )
                return@get
            }
            val pkceParts = pkcePayload.split("|")
            if (pkceParts.size != 3) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AdminView.errorPage("Session mismatch. Please try again.", "/admin/login"),
                )
                return@get
            }
            val verifier = pkceParts[0]
            val timestamp = pkceParts[1].toLongOrNull() ?: 0L
            val state = pkceParts[2]
            if (System.currentTimeMillis() - timestamp > 300_000) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AdminView.errorPage("Login session expired. Please try again.", "/admin/login"),
                )
                return@get
            }
            val callbackState = call.request.queryParameters["state"]
            if (callbackState != state) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AdminView.errorPage("Invalid state parameter. Please try again.", "/admin/login"),
                )
                return@get
            }

            // Clear the PKCE cookie
            call.response.cookies.append(
                name = "KOTAUTH_ADMIN_PKCE",
                value = "",
                maxAge = 0L,
                path = "/admin",
                httpOnly = true,
            )

            // Handle OAuth error responses
            val errorParam = call.request.queryParameters["error"]
            if (errorParam != null) {
                val errorMsg =
                    when (errorParam) {
                        "access_denied" -> "Access denied. Your account may not have admin console access."
                        "invalid_client" -> "Admin console configuration error. Contact your administrator."
                        else -> "Authentication failed. Please try again."
                    }
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AdminView.errorPage(errorMsg, "/admin/login"),
                )
                return@get
            }

            val code = call.request.queryParameters["code"]
            if (code.isNullOrBlank()) {
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AdminView.errorPage("Missing authorization code. Please try again.", "/admin/login"),
                )
                return@get
            }

            val redirectUri = "$baseUrl/admin/callback"
            val ipAddress = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"]
            val tokenResult =
                oauthService?.exchangeAuthorizationCode(
                    tenantSlug = Tenant.MASTER_SLUG,
                    code = code,
                    clientId = AdminClientProvisioning.ADMIN_CLIENT_ID,
                    redirectUri = redirectUri,
                    codeVerifier = verifier,
                    clientSecret = null,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                )

            if (tokenResult == null || tokenResult is OAuthResult.Failure) {
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AdminView.errorPage("Token exchange failed. Please try again.", "/admin/login"),
                )
                return@get
            }

            val accessToken = (tokenResult as OAuthResult.Success).value.access_token
            val idToken = (tokenResult as OAuthResult.Success).value.id_token ?: ""
            val claims = decodeJwtPayload(accessToken)
            val userId = claims["sub"]?.toIntOrNull()
            val username = claims["preferred_username"] ?: ""
            val masterTenant = tenantRepository.findBySlug(Tenant.MASTER_SLUG)

            if (userId == null || masterTenant == null) {
                call.respondHtml(
                    HttpStatusCode.InternalServerError,
                    AdminView.errorPage("Could not establish admin session.", "/admin/login"),
                )
                return@get
            }

            // Verify user has admin role on master tenant
            val userRoles = roleRepository?.findRolesForUser(UserId(userId)) ?: emptyList()
            val hasAdminRole = userRoles.any { it.name == "admin" && it.tenantId == masterTenant.id }
            if (!hasAdminRole) {
                call.respondHtml(
                    HttpStatusCode.Forbidden,
                    AdminView.errorPage(
                        "Your account does not have admin console access. " +
                            "Contact your administrator to request the admin role.",
                        "/admin/login",
                    ),
                )
                return@get
            }

            // Find the session record created by the token exchange
            val latestSession =
                selfServiceService
                    ?.getActiveSessions(UserId(userId), masterTenant.id)
                    ?.maxByOrNull { it.createdAt }

            call.sessions.set(
                AdminSession(
                    userId = userId,
                    tenantId = masterTenant.id.value,
                    username = username,
                    accessToken = accessToken,
                    idToken = idToken,
                    adminSessionId = latestSession?.id?.value,
                ),
            )
            call.respondRedirect("/admin")
        }

        // ---------------------------------------------------------------
        // Logout — revoke session + OIDC end-session
        // ---------------------------------------------------------------

        post("/logout") {
            val session = call.sessions.get<AdminSession>()
            if (session?.adminSessionId != null) {
                selfServiceService?.revokeSession(
                    UserId(session.userId),
                    TenantId(session.tenantId),
                    com.kauth.domain.model
                        .SessionId(session.adminSessionId),
                )
            }
            call.sessions.clear<AdminSession>()
            val postLogoutUri = java.net.URLEncoder.encode("$baseUrl/admin/login", "UTF-8")
            val idTokenHint =
                if (session?.idToken?.isNotBlank() == true) {
                    "&id_token_hint=${java.net.URLEncoder.encode(session.idToken, "UTF-8")}"
                } else {
                    ""
                }
            call.respondRedirect(
                "/t/master/protocol/openid-connect/logout?post_logout_redirect_uri=$postLogoutUri$idTokenHint",
            )
        }

        // ---------------------------------------------------------------
        // Session guard
        // ---------------------------------------------------------------

        intercept(ApplicationCallPipeline.Call) {
            val uri = call.request.uri
            if (uri.startsWith("/admin/login") || uri.startsWith("/admin/callback")) return@intercept
            if (call.sessions.get<AdminSession>() == null) {
                call.respondRedirect("/admin/login")
                finish()
            }
        }

        // ---------------------------------------------------------------
        // Smart redirect: last workspace → first workspace → create
        // ---------------------------------------------------------------

        get {
            val workspaces = tenantRepository.findAll().filter { !it.isMaster }
            if (workspaces.isEmpty()) {
                call.respondRedirect("/admin/workspaces/new")
                return@get
            }
            // Prefer last-visited workspace (cookie), fall back to first available
            val lastSlug = call.request.cookies["kotauth_last_ws"]
            val target = workspaces.firstOrNull { it.slug == lastSlug } ?: workspaces.first()
            call.respondRedirect("/admin/workspaces/${target.slug}")
        }

        // ===============================================================
        // Workspaces
        // ===============================================================

        route("/workspaces") {
            get {
                val session = call.sessions.get<AdminSession>()!!
                val workspaces = tenantRepository.findAll()
                val wsPairs = workspaces.map { it.slug to it.displayName }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.workspaceListPage(workspaces, wsPairs, session.username),
                )
            }

            get("/new") {
                val session = call.sessions.get<AdminSession>()!!
                val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                call.respondHtml(
                    HttpStatusCode.OK,
                    AdminView.createWorkspacePage(loggedInAs = session.username, allWorkspaces = wsPairs),
                )
            }

            post {
                val session = call.sessions.get<AdminSession>()!!
                val params = call.receiveParameters()
                val slug = params["slug"]?.trim()?.lowercase() ?: ""
                val displayName = params["displayName"]?.trim() ?: ""
                val issuerUrl = params["issuerUrl"]?.trim()?.takeIf { it.isNotBlank() }
                val prefill =
                    WorkspacePrefill(
                        slug = slug,
                        displayName = displayName,
                        issuerUrl = issuerUrl ?: "",
                        registrationEnabled = params["registrationEnabled"] == "true",
                        emailVerificationRequired = params["emailVerificationRequired"] == "true",
                        themeAccentColor = params["themeAccentColor"]?.trim() ?: "#1FBCFF",
                        themeLogoUrl = params["themeLogoUrl"]?.trim() ?: "",
                    )
                val error =
                    when {
                        slug.isBlank() -> "Slug is required."
                        !slug.matches(
                            Regex("[a-z0-9-]+"),
                        ) -> "Slug may only contain lowercase letters, numbers, and hyphens."
                        slug == Tenant.MASTER_SLUG -> "The slug 'master' is reserved."
                        displayName.isBlank() -> "Display name is required."
                        tenantRepository.existsBySlug(slug) -> "A workspace with slug '$slug' already exists."
                        else -> null
                    }
                if (error != null) {
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    return@post call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.createWorkspacePage(
                            loggedInAs = session.username,
                            allWorkspaces = wsPairs,
                            error = error,
                            prefill = prefill,
                        ),
                    )
                }
                val newTenant = tenantRepository.create(slug, displayName, issuerUrl)
                keyProvisioningService.provisionForTenant(newTenant)
                portalClientProvisioning?.provisionRedirectUris()
                roleGroupService.createRole(
                    newTenant.id,
                    "admin",
                    "Full administrative access within this workspace",
                    RoleScope.TENANT,
                    null,
                )
                roleGroupService.createRole(
                    newTenant.id,
                    "user",
                    "Standard authenticated user — default role for self-registrations",
                    RoleScope.TENANT,
                    null,
                )
                call.respondRedirect("/admin/workspaces/$slug")
            }

            // -----------------------------------------------------------
            // Per-workspace routes
            // -----------------------------------------------------------

            route("/{slug}") {
                // Resolve workspace + sidebar pairs once per request
                intercept(ApplicationCallPipeline.Call) {
                    val slug =
                        call.parameters["slug"]
                            ?: return@intercept call.respond(HttpStatusCode.BadRequest).also { finish() }
                    val workspace =
                        tenantRepository.findBySlug(slug)
                            ?: return@intercept call.respond(HttpStatusCode.NotFound).also { finish() }
                    val wsPairs = tenantRepository.findAll().map { it.slug to it.displayName }
                    call.attributes.put(WorkspaceAttr, workspace)
                    call.attributes.put(WsPairsAttr, wsPairs)
                    // Remember last-visited workspace for the /admin redirect
                    call.response.cookies.append("kotauth_last_ws", slug, path = "/admin", maxAge = 86400 * 30L)
                }

                get {
                    val session = call.sessions.get<AdminSession>()!!
                    val workspace = call.attributes[WorkspaceAttr]
                    val wsPairs = call.attributes[WsPairsAttr]
                    val apps = applicationRepository.findByTenantId(workspace.id)
                    call.respondHtml(
                        HttpStatusCode.OK,
                        AdminView.workspaceDetailPage(workspace, wsPairs, apps, session.username),
                    )
                }

                adminSettingsRoutes(
                    adminService = adminService,
                    userRepository = userRepository,
                    identityProviderRepository = identityProviderRepository,
                    mfaRepository = mfaRepository,
                    encryptionService = encryptionService,
                )

                adminApplicationRoutes(
                    adminService = adminService,
                    applicationRepository = applicationRepository,
                )

                adminUserRoutes(
                    adminService = adminService,
                    roleGroupService = roleGroupService,
                    userRepository = userRepository,
                    sessionRepository = sessionRepository,
                )

                adminSessionAuditRoutes(
                    sessionRepository = sessionRepository,
                    auditLogRepository = auditLogRepository,
                )

                adminRbacRoutes(
                    roleGroupService = roleGroupService,
                    applicationRepository = applicationRepository,
                    userRepository = userRepository,
                )

                adminApiKeyRoutes(
                    apiKeyService = apiKeyService,
                )

                adminWebhookRoutes(
                    webhookService = webhookService,
                )
            }
        }
    }
}

// =============================================================================
// PKCE + JWT helpers — same as PortalRoutes.kt
// =============================================================================

private fun generatePkceVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun generatePkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun decodeJwtPayload(jwt: String): Map<String, String> {
    return try {
        val parts = jwt.split(".")
        if (parts.size < 2) return emptyMap()
        val payload =
            String(
                Base64.getUrlDecoder().decode(
                    parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='),
                ),
                Charsets.UTF_8,
            )
        val jsonElement = Json.parseToJsonElement(payload)
        val obj = jsonElement as? JsonObject ?: return emptyMap()
        obj.entries.associate { (k, v) ->
            k to
                when (v) {
                    is JsonPrimitive -> v.content
                    else -> v.toString()
                }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
