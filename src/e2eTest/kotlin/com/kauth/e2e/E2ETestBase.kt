package com.kauth.e2e

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.admin.AdminSession
import com.kauth.adapter.web.admin.adminRoutes
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.domain.service.WebhookService
import com.kauth.fakes.FakeApiKeyRepository
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
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

abstract class E2ETestBase {
    companion object {
        lateinit var server: NettyApplicationEngine
        lateinit var playwright: Playwright
        lateinit var browser: Browser
        var port: Int = 0
        val baseUrl get() = "http://localhost:$port"

        val tenantRepo = FakeTenantRepository()
        val userRepo = FakeUserRepository()
        val appRepo = FakeApplicationRepository()
        val sessionRepo = FakeSessionRepository()
        val roleRepo = FakeRoleRepository()
        val groupRepo = FakeGroupRepository()
        val auditLogRepo = FakeAuditLogRepository()
        val auditLogPort = FakeAuditLogPort()
        val apiKeyRepo = FakeApiKeyRepository()
        val webhookEndpointRepo = FakeWebhookEndpointRepository()
        val webhookDeliveryRepo = FakeWebhookDeliveryRepository()
        val hasher = FakePasswordHasher()
        val tokenPort = FakeTokenPort()
        val encryptionService = EncryptionService("e2e-test-secret-key-32chars!!")
        val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)

        val masterTenant =
            Tenant(
                id = TenantId(1),
                slug = "master",
                displayName = "Master",
                issuerUrl = null,
                theme = TenantTheme.DEFAULT,
            )

        val adminUser =
            User(
                id = UserId(1),
                tenantId = TenantId(1),
                username = "admin",
                email = "admin@kotauth.dev",
                fullName = "Admin User",
                passwordHash = hasher.hash("admin-pass"),
                enabled = true,
            )

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

        private fun buildApiKeyService() =
            ApiKeyService(
                apiKeyRepository = apiKeyRepo,
                tenantRepository = tenantRepo,
            )

        private fun buildWebhookService() =
            WebhookService(
                endpointRepository = webhookEndpointRepo,
                deliveryRepository = webhookDeliveryRepo,
            )

        private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @BeforeAll
        fun startServer() {
            port = findFreePort()

            server =
                embeddedServer(Netty, port = port) {
                    install(ContentNegotiation) { json() }
                    install(Sessions) {
                        cookie<AdminSession>("KOTAUTH_ADMIN") {
                            transform(
                                io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication(
                                    ByteArray(32),
                                ),
                            )
                        }
                    }
                    install(StatusPages) {
                        exception<Throwable> { call, cause ->
                            cause.printStackTrace()
                            call.respondText(
                                "E2E Server Error: ${cause.message}",
                                status = HttpStatusCode.InternalServerError,
                            )
                        }
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
                            apiKeyService = buildApiKeyService(),
                            webhookService = buildWebhookService(),
                            encryptionService = encryptionService,
                            roleRepository = roleRepo,
                            adminBypass = true,
                        )
                    }
                }
            server.start(wait = false)
            waitForServer()

            playwright = Playwright.create()
            val headless = System.getProperty("playwright.headless", "true").toBoolean()
            browser =
                playwright.chromium().launch(
                    com.microsoft.playwright.BrowserType
                        .LaunchOptions()
                        .setHeadless(headless),
                )
        }

        private fun waitForServer(
            timeoutMs: Long = 10_000,
            intervalMs: Long = 200,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val conn = URL("$baseUrl/admin/login").openConnection() as HttpURLConnection
                    conn.connectTimeout = 500
                    conn.readTimeout = 500
                    conn.requestMethod = "GET"
                    if (conn.responseCode in 200..499) return
                } catch (_: Exception) {
                    // server not ready yet
                }
                Thread.sleep(intervalMs)
            }
            error("Server did not become ready within ${timeoutMs}ms")
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            browser.close()
            playwright.close()
            server.stop(500, 1000)
        }
    }

    protected lateinit var context: BrowserContext
    protected lateinit var page: Page

    @BeforeEach
    fun setupBrowser() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        sessionRepo.clear()
        roleRepo.clear()
        groupRepo.clear()
        auditLogPort.clear()
        apiKeyRepo.clear()
        webhookEndpointRepo.clear()
        webhookDeliveryRepo.clear()
        tokenPort.reset()

        tenantRepo.add(masterTenant)
        userRepo.add(adminUser)
        // Seed admin role + assignment for bypass login role check
        val adminRole =
            roleRepo.add(
                com.kauth.domain.model.Role(
                    tenantId = TenantId(1),
                    name = "admin",
                    scope = com.kauth.domain.model.RoleScope.TENANT,
                ),
            )
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)

        context = browser.newContext()
        page = context.newPage()
    }

    @AfterEach
    fun teardownBrowser() {
        context.close()
    }

    protected fun navigateSafe(url: String) {
        page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
    }

    protected fun waitForUrlPattern(glob: String) {
        page.waitForURL(
            glob,
            Page
                .WaitForURLOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(5000.0),
        )
    }

    protected fun loginAsAdmin() {
        navigateSafe("$baseUrl/admin/login")
        page.fill("input[name=username]", "admin")
        page.fill("input[name=password]", "admin-pass")
        page.click("button[type=submit]")
        waitForUrlPattern("**/admin/**")
    }
}
