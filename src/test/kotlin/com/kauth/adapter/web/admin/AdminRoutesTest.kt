package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for [adminRoutes] — session guard and login/logout.
 *
 * Focus: the authentication boundary, not the 70+ CRUD endpoints behind it.
 * CRUD is already validated at the domain service layer.
 */
class AdminRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val tokenPort = FakeTokenPort()

    private val masterTenant =
        Tenant(
            id = TenantId(1),
            slug = "master",
            displayName = "Master",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val adminUser =
        User(
            id = UserId(1),
            tenantId = TenantId(1),
            username = "admin",
            email = "admin@kotauth.dev",
            fullName = "Admin",
            passwordHash = hasher.hash("admin-pass"),
            enabled = true,
        )

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key")

    private fun buildSelfService() =
        UserSelfServiceService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            evTokenRepo = FakeEmailVerificationTokenRepository(),
            prTokenRepo = FakePasswordResetTokenRepository(),
            emailPort = FakeEmailPort(),
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun buildAdminService() =
        AdminService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            sessionRepository = sessionRepo,
            selfServiceService = buildSelfService(),
        )

    private fun buildRoleGroupService() =
        RoleGroupService(
            roleRepository = roleRepo,
            groupRepository = groupRepo,
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            auditLog = auditLogPort,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        sessionRepo.clear()
        auditLogPort.clear()
        tokenPort.reset()
        tenantRepo.add(masterTenant)
        userRepo.add(adminUser)
        // Seed admin role and assign to admin user (required for OAuth callback role check)
        val adminRole =
            roleRepo.add(
                com.kauth.domain.model.Role(
                    tenantId = TenantId(1),
                    name = "admin",
                    scope = com.kauth.domain.model.RoleScope.TENANT,
                ),
            )
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)
    }

    // =========================================================================
    // Session guard — unauthenticated access
    // =========================================================================

    @Test
    fun `GET admin redirects to login when no session cookie is present`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(location.contains("/admin/login"))
        }

    @Test
    fun `GET admin workspaces redirects to login when unauthenticated`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin/workspaces")

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/admin/login") == true)
        }

    // =========================================================================
    // POST /admin/logout
    // =========================================================================

    @Test
    fun `POST admin logout without session redirects to login`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/admin/logout",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("/admin/login"),
                "Unauthenticated logout must redirect to login, got: $location",
            )
        }

    // =========================================================================
    // Session guard — callback is accessible without session
    // =========================================================================

    @Test
    fun `GET admin callback is reachable without an active session`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            // No PKCE cookie → expect an error page (BadRequest), NOT a redirect to /admin/login.
            // This proves the session guard does not intercept /admin/callback.
            val response = noFollow.get("/admin/callback?code=test-code&state=$testState")

            assertFalse(
                response.status == HttpStatusCode.Found &&
                    response.headers["Location"]?.contains("/admin/login") == true,
                "Session guard must not intercept /admin/callback — got redirect to login instead of error page",
            )
        }

    // =========================================================================
    // OAuth flow — GET /admin/login
    // =========================================================================

    @Test
    fun `GET admin login redirects to OAuth authorization endpoint`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin/login")

            assertEquals(
                HttpStatusCode.Found,
                response.status,
                "Must redirect to OAuth provider — expected 302",
            )
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("/t/master/authorize"),
                "Redirect must target master-tenant OIDC auth endpoint, got: $location",
            )
        }

    @Test
    fun `GET admin login OAuth redirect contains correct client_id`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin/login")

            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("client_id=kotauth-admin"),
                "OAuth redirect must carry client_id=kotauth-admin, got: $location",
            )
        }

    @Test
    fun `GET admin login OAuth redirect uses S256 PKCE challenge method`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin/login")

            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("code_challenge_method=S256"),
                "OAuth redirect must specify S256 challenge method, got: $location",
            )
        }

    @Test
    fun `GET admin login sets signed PKCE state cookie`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin/login")

            val cookies = response.headers.getAll("Set-Cookie") ?: emptyList()
            assertTrue(
                cookies.any { it.contains("KOTAUTH_ADMIN_PKCE") },
                "A KOTAUTH_ADMIN_PKCE cookie must be set to carry the PKCE verifier",
            )
        }

    @Test
    fun `GET admin login PKCE cookie is HttpOnly and has short max-age`() =
        testApplication {
            application { installOAuthTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin/login")

            val pkceCookie =
                response.headers
                    .getAll("Set-Cookie")
                    ?.firstOrNull { it.contains("KOTAUTH_ADMIN_PKCE") }
                    ?: ""
            assertTrue(pkceCookie.contains("HttpOnly", ignoreCase = true), "PKCE cookie must be HttpOnly")
            // Max-Age should be 300 seconds or less
            val maxAge =
                Regex("Max-Age=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(pkceCookie)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            assertTrue(maxAge != null && maxAge <= 300, "PKCE cookie Max-Age must be ≤ 300s, got: $maxAge")
        }

    // =========================================================================
    // OAuth callback — missing PKCE cookie
    // =========================================================================

    @Test
    fun `GET admin callback without PKCE cookie returns 400 error page`() =
        testApplication {
            application { installOAuthTestApp() }

            val response = client.get("/admin/callback?code=some-auth-code&state=$testState")

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "Callback without PKCE cookie must return 400",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("Session expired") || body.contains("try again"),
                "Error page must guide user to retry, got: $body",
            )
        }

    // =========================================================================
    // OAuth callback — tampered / invalid PKCE cookie
    // =========================================================================

    @Test
    fun `GET admin callback with tampered PKCE cookie returns 400 error page`() =
        testApplication {
            application { installOAuthTestApp() }

            val response =
                client.get("/admin/callback?code=some-auth-code&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=tampered.invalidsignature")
                }

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "Callback with tampered PKCE cookie must return 400",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("Invalid session") || body.contains("try again"),
                "Error page must indicate invalid session state, got: $body",
            )
        }

    // =========================================================================
    // OAuth callback — OAuth error query parameter
    // =========================================================================

    @Test
    fun `GET admin callback with error=access_denied returns 401 with access denied message`() =
        testApplication {
            application { installOAuthTestApp() }

            // A valid signed PKCE cookie is required — the error param check happens after cookie
            // verification, so we must pass a well-formed cookie to reach that branch.
            val pkceCookieValue = buildValidPkceCookie()

            val response =
                client.get("/admin/callback?error=access_denied&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "access_denied OAuth error must result in 401",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("Access denied", ignoreCase = true),
                "Error page must contain 'Access denied' for access_denied OAuth error, got: $body",
            )
        }

    @Test
    fun `GET admin callback with error=invalid_client returns 401 with configuration error message`() =
        testApplication {
            application { installOAuthTestApp() }

            val pkceCookieValue = buildValidPkceCookie()

            val response =
                client.get("/admin/callback?error=invalid_client&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("configuration error", ignoreCase = true) ||
                    body.contains("administrator", ignoreCase = true),
                "invalid_client error must surface as configuration error, got: $body",
            )
        }

    @Test
    fun `GET admin callback with unknown OAuth error returns 401 with generic message`() =
        testApplication {
            application { installOAuthTestApp() }

            val pkceCookieValue = buildValidPkceCookie()

            val response =
                client.get("/admin/callback?error=server_error&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("Authentication failed", ignoreCase = true) ||
                    body.contains("try again", ignoreCase = true),
                "Unknown OAuth error must show generic failure message, got: $body",
            )
        }

    // =========================================================================
    // OAuth callback — missing auth code (no error param, no code param)
    // =========================================================================

    @Test
    fun `GET admin callback with no code and no error returns 400`() =
        testApplication {
            application { installOAuthTestApp() }

            val pkceCookieValue = buildValidPkceCookie()

            val response =
                client.get("/admin/callback?state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "Callback with neither code nor error must return 400",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("Missing authorization code", ignoreCase = true),
                "Error page must mention missing authorization code, got: $body",
            )
        }

    // =========================================================================
    // OAuth callback — oauthService not wired (simulates unconfigured server)
    // =========================================================================

    @Test
    fun `GET admin callback returns 401 when oauthService is null and code is present`() =
        testApplication {
            application { installOAuthTestApp() }

            // installOAuthTestApp wires oauthService = null intentionally.
            // The route must handle this gracefully rather than throwing.
            val pkceCookieValue = buildValidPkceCookie()

            val response =
                client.get("/admin/callback?code=valid-looking-code&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "When oauthService is null the token exchange returns null — must respond 401",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("Token exchange failed", ignoreCase = true),
                "Error page must indicate token exchange failure, got: $body",
            )
        }

    // =========================================================================
    // OAuth callback — no admin role on master tenant
    // =========================================================================

    @Test
    fun `GET admin callback returns 403 when authenticated user lacks admin role on master tenant`() =
        testApplication {
            application { installOAuthTestAppWithOAuth(adminRole = false) }

            val pkceCookieValue = buildValidPkceCookie()
            val fakeJwt = buildFakeJwt(userId = 1, username = "admin")

            val response =
                client.get("/admin/callback?code=$fakeJwt&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "User without admin role must receive 403 from the callback",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("admin console access", ignoreCase = true) ||
                    body.contains("admin role", ignoreCase = true),
                "Error must explain the access requirement, got: $body",
            )
        }

    @Test
    fun `GET admin callback grants access and redirects to admin dashboard when user has admin role`() =
        testApplication {
            application { installOAuthTestAppWithOAuth(adminRole = true) }

            val noFollow = createClient { followRedirects = false }
            val pkceCookieValue = buildValidPkceCookie()
            val fakeJwt = buildFakeJwt(userId = 1, username = "admin")

            val response =
                noFollow.get("/admin/callback?code=$fakeJwt&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            assertEquals(
                HttpStatusCode.Found,
                response.status,
                "Successful callback must redirect to /admin",
            )
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.endsWith("/admin") || location == "/admin",
                "Must redirect to /admin dashboard after successful OAuth login, got: $location",
            )
            val cookies = response.headers.getAll("Set-Cookie") ?: emptyList()
            assertTrue(
                cookies.any { it.contains("KOTAUTH_ADMIN") },
                "AdminSession cookie must be set after successful OAuth login",
            )
        }

    @Test
    fun `GET admin callback clears PKCE cookie after processing`() =
        testApplication {
            application { installOAuthTestApp() }

            val pkceCookieValue = buildValidPkceCookie()

            val response =
                client.get("/admin/callback?code=any-code&state=$testState") {
                    header("Cookie", "KOTAUTH_ADMIN_PKCE=$pkceCookieValue")
                }

            // The route clears the PKCE cookie regardless of outcome.
            // The cleared cookie Set-Cookie header will have maxAge=0 or an empty value.
            val cookies = response.headers.getAll("Set-Cookie") ?: emptyList()
            val pkceCookieHeader = cookies.firstOrNull { it.contains("KOTAUTH_ADMIN_PKCE") } ?: ""
            assertTrue(
                pkceCookieHeader.isNotEmpty(),
                "A KOTAUTH_ADMIN_PKCE Set-Cookie header must be present to clear the cookie",
            )
            // Cleared cookie is identifiable by Max-Age=0 or an empty value field
            val isCleared =
                pkceCookieHeader.contains("Max-Age=0") ||
                    pkceCookieHeader.contains("KOTAUTH_ADMIN_PKCE=;") ||
                    Regex("KOTAUTH_ADMIN_PKCE=\\s*;").containsMatchIn(pkceCookieHeader) ||
                    Regex("KOTAUTH_ADMIN_PKCE=\\s*$").containsMatchIn(pkceCookieHeader)
            assertTrue(
                isCleared,
                "PKCE cookie must be cleared (Max-Age=0 or empty value) after callback processing, got: $pkceCookieHeader",
            )
        }

    // =========================================================================
    // Test app wiring
    // =========================================================================

    /**
     * Test app with oauthService=null.
     * Used to verify all PKCE redirect logic and callback guard rails without
     * a real token exchange (null oauthService exercises the failure branch).
     */
    private fun io.ktor.server.application.Application.installOAuthTestApp() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            adminRoutes(
                adminService = buildAdminService(),
                roleGroupService = buildRoleGroupService(),
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                encryptionService = encryptionService,
                oauthService = null,
            )
        }
    }

    /**
     * Test app with a mockk OAuthService stub.
     *
     * The stub returns an [OAuthResult.Success] whose access_token equals whatever
     * code was passed in — this lets the test craft a fake JWT as the "code" so that
     * [decodeJwtPayload] inside the route can parse `sub` and `preferred_username`.
     *
     * When [adminRole] is true the admin user is given the "admin" role on the master
     * tenant so the role-check branch succeeds. When false the role check fails → 403.
     */
    private fun io.ktor.server.application.Application.installOAuthTestAppWithOAuth(adminRole: Boolean) {
        roleRepo.clear()
        if (adminRole) {
            val role =
                roleRepo.add(
                    Role(
                        id = RoleId(1),
                        tenantId = TenantId(1),
                        name = "admin",
                        scope = RoleScope.TENANT,
                    ),
                )
            roleRepo.assignRoleToUser(UserId(1), role.id!!)
        }

        val oauthSvcMock = mockk<com.kauth.domain.service.OAuthService>(relaxed = true)
        every {
            oauthSvcMock.exchangeAuthorizationCode(
                tenantSlug = any(),
                code = any(),
                clientId = any(),
                redirectUri = any(),
                codeVerifier = any(),
                clientSecret = any(),
                ipAddress = any(),
                userAgent = any(),
            )
        } answers {
            // secondArg() is `code` — return it as access_token so decodeJwtPayload can parse the fake JWT
            val code = secondArg<String>()
            com.kauth.domain.service.OAuthResult.Success(
                com.kauth.domain.model.TokenResponse(
                    access_token = code,
                    token_type = "Bearer",
                    expires_in = 300,
                ),
            )
        }

        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            adminRoutes(
                adminService = buildAdminService(),
                roleGroupService = buildRoleGroupService(),
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                encryptionService = encryptionService,
                oauthService = oauthSvcMock,
                selfServiceService = buildSelfService(),
                roleRepository = roleRepo,
            )
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Produces a signed PKCE cookie value using the same EncryptionService and
     * secret key that the test app uses. The embedded timestamp is current so
     * the 5-minute expiry check inside the callback will pass.
     */
    private val testState = "test-state-xxxx-yyyy-zzzz"

    private fun buildValidPkceCookie(): String {
        val verifier = "test-pkce-verifier-aaaa-bbbb-cccc-dddd-eeee"
        val payload = "$verifier|${System.currentTimeMillis()}|$testState"
        return encryptionService.signCookie(payload)
    }

    /**
     * Builds a minimal fake JWT whose payload encodes [userId] and [username]
     * in the format that [decodeJwtPayload] inside AdminRoutes.kt can parse.
     *
     * Format: "header.payload.sig" where payload is base64url-encoded JSON.
     * The regex inside decodeJwtPayload matches: "key": "value" or "key": 123
     */
    private fun buildFakeJwt(
        userId: Int,
        username: String,
    ): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val payload =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"sub":$userId,"preferred_username":"$username"}""".toByteArray(),
            )
        return "$header.$payload.fakesig"
    }
}
