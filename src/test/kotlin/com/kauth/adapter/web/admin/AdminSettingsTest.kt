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
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.plugins.cookies.HttpCookies
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
 * Integration tests for admin workspace settings routes:
 * general settings, SMTP, security policy, branding.
 *
 * All routes live behind the session guard — tests login first,
 * then exercise the settings endpoints with a cookie jar.
 */
class AdminSettingsTest {
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

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)

    private val masterTenant = Tenant(
        id = 1, slug = "master", displayName = "Master",
        issuerUrl = null, theme = TenantTheme.DEFAULT,
    )

    private val workspace = Tenant(
        id = 2, slug = "acme", displayName = "Acme Corp",
        issuerUrl = null, theme = TenantTheme.DEFAULT,
    )

    private val adminUser = User(
        id = 1, tenantId = 1, username = "admin",
        email = "admin@kotauth.dev", fullName = "Admin",
        passwordHash = hasher.hash("admin-pass"), enabled = true,
    )

    private fun buildAuthService() = AuthService(
        userRepository = userRepo, tenantRepository = tenantRepo,
        tokenPort = tokenPort, passwordHasher = hasher,
        auditLog = auditLogPort, sessionRepository = sessionRepo,
    )

    private fun buildSelfService() = UserSelfServiceService(
        userRepository = userRepo, tenantRepository = tenantRepo,
        sessionRepository = sessionRepo, passwordHasher = hasher,
        auditLog = auditLogPort, evTokenRepo = FakeEmailVerificationTokenRepository(),
        prTokenRepo = FakePasswordResetTokenRepository(), emailPort = FakeEmailPort(),
    )

    private fun buildAdminService() = AdminService(
        tenantRepository = tenantRepo, userRepository = userRepo,
        applicationRepository = appRepo, passwordHasher = hasher,
        auditLog = auditLogPort, sessionRepository = sessionRepo,
        selfServiceService = buildSelfService(),
    )

    private fun buildRoleGroupService() = RoleGroupService(
        roleRepository = roleRepo, groupRepository = groupRepo,
        tenantRepository = tenantRepo, userRepository = userRepo,
        applicationRepository = appRepo, auditLog = auditLogPort,
    )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        auditLogPort.clear()
        tokenPort.reset()
        tenantRepo.add(masterTenant)
        tenantRepo.add(workspace)
        userRepo.add(adminUser)
    }

    // =========================================================================
    // General workspace settings
    // =========================================================================

    @Test
    fun `GET workspace settings returns 200 for authenticated admin`() = testApplication {
        application { installTestApp() }
        val authed = createClient { install(HttpCookies) }
        login(authed)

        val response = authed.get("/admin/workspaces/acme/settings")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST workspace settings saves and redirects with saved flag`() = testApplication {
        application { installTestApp() }
        val authed = createClient {
            install(HttpCookies)
            followRedirects = false
        }
        login(authed)

        val response = authed.submitForm(
            url = "/admin/workspaces/acme/settings",
            formParameters = Parameters.build {
                append("displayName", "Acme Updated")
                append("tokenExpirySeconds", "7200")
                append("refreshTokenExpirySeconds", "172800")
                append("registrationEnabled", "true")
            },
        )

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers["Location"]?.contains("saved=true") == true)
    }

    // =========================================================================
    // SMTP settings
    // =========================================================================

    @Test
    fun `GET smtp settings returns 200 for authenticated admin`() = testApplication {
        application { installTestApp() }
        val authed = createClient { install(HttpCookies) }
        login(authed)

        val response = authed.get("/admin/workspaces/acme/settings/smtp")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST smtp settings saves and redirects with saved flag`() = testApplication {
        application { installTestApp() }
        val authed = createClient {
            install(HttpCookies)
            followRedirects = false
        }
        login(authed)

        val response = authed.submitForm(
            url = "/admin/workspaces/acme/settings/smtp",
            formParameters = Parameters.build {
                append("smtpHost", "smtp.example.com")
                append("smtpPort", "587")
                append("smtpFromAddress", "no-reply@acme.dev")
                append("smtpTlsEnabled", "true")
                append("smtpEnabled", "true")
            },
        )

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers["Location"]?.contains("saved=true") == true)
    }

    // =========================================================================
    // Security policy settings
    // =========================================================================

    @Test
    fun `GET security settings returns 200 for authenticated admin`() = testApplication {
        application { installTestApp() }
        val authed = createClient { install(HttpCookies) }
        login(authed)

        val response = authed.get("/admin/workspaces/acme/settings/security")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST security settings saves password policy and redirects`() = testApplication {
        application { installTestApp() }
        val authed = createClient {
            install(HttpCookies)
            followRedirects = false
        }
        login(authed)

        val response = authed.submitForm(
            url = "/admin/workspaces/acme/settings/security",
            formParameters = Parameters.build {
                append("passwordPolicyMinLength", "12")
                append("passwordPolicyRequireSpecial", "true")
                append("passwordPolicyRequireUppercase", "true")
                append("passwordPolicyRequireNumber", "true")
                append("mfaPolicy", "required")
            },
        )

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers["Location"]?.contains("saved=true") == true)
    }

    // =========================================================================
    // Branding settings
    // =========================================================================

    @Test
    fun `GET branding settings returns 200 for authenticated admin`() = testApplication {
        application { installTestApp() }
        val authed = createClient { install(HttpCookies) }
        login(authed)

        val response = authed.get("/admin/workspaces/acme/settings/branding")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST workspace settings for unknown slug returns 404`() = testApplication {
        application { installTestApp() }
        val authed = createClient { install(HttpCookies) }
        login(authed)

        val response = authed.get("/admin/workspaces/ghost/settings")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private suspend fun login(client: io.ktor.client.HttpClient) {
        client.submitForm(
            url = "/admin/login",
            formParameters = Parameters.build {
                append("username", "admin")
                append("password", "admin-pass")
            },
        )
    }

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
            )
        }
    }
}
