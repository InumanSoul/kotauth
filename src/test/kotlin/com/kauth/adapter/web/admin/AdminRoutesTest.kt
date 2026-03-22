package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.AuthService
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
            id = 1,
            slug = "master",
            displayName = "Master",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val adminUser =
        User(
            id = 1,
            tenantId = 1,
            username = "admin",
            email = "admin@kotauth.dev",
            fullName = "Admin",
            passwordHash = hasher.hash("admin-pass"),
            enabled = true,
        )

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key")

    private fun buildAuthService() =
        AuthService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            sessionRepository = sessionRepo,
        )

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
        auditLogPort.clear()
        tokenPort.reset()
        tenantRepo.add(masterTenant)
        userRepo.add(adminUser)
    }

    // =========================================================================
    // Session guard — unauthenticated access
    // =========================================================================

    @Test
    fun `GET admin redirects to login when no session cookie is present`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(location.contains("/admin/login"))
        }

    @Test
    fun `GET admin workspaces redirects to login when unauthenticated`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/admin/workspaces")

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/admin/login") == true)
        }

    // =========================================================================
    // GET /admin/login
    // =========================================================================

    @Test
    fun `GET admin login returns 200 with login form`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/admin/login")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("login") || body.contains("Login") || body.contains("form"))
        }

    // =========================================================================
    // POST /admin/login
    // =========================================================================

    @Test
    fun `POST admin login with valid credentials sets session and redirects`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/admin/login",
                    formParameters =
                        Parameters.build {
                            append("username", "admin")
                            append("password", "admin-pass")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(location.endsWith("/admin") || location.contains("/admin"), "Must redirect to /admin")
            // Session cookie must be set
            val cookies = response.headers.getAll("Set-Cookie")
            assertTrue(
                cookies?.any { it.contains("KOTAUTH_ADMIN") } == true,
                "KOTAUTH_ADMIN session cookie must be set on successful login",
            )
        }

    @Test
    fun `POST admin login with invalid credentials returns 401`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.submitForm(
                    url = "/admin/login",
                    formParameters =
                        Parameters.build {
                            append("username", "admin")
                            append("password", "wrong-password")
                        },
                )

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Invalid credentials"))
        }

    @Test
    fun `POST admin login with blank username returns 401`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.submitForm(
                    url = "/admin/login",
                    formParameters =
                        Parameters.build {
                            append("username", "")
                            append("password", "admin-pass")
                        },
                )

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // =========================================================================
    // POST /admin/logout
    // =========================================================================

    @Test
    fun `POST admin logout redirects to login`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/admin/logout",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/admin/login") == true)
        }

    // =========================================================================
    // Test app wiring
    // =========================================================================

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN")
        }
        routing {
            adminRoutes(
                authService = buildAuthService(),
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
            )
        }
    }
}
