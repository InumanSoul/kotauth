package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.SecurityMethodsService
import com.kauth.domain.service.WorkspaceSettingsService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
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
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthMethodsGridTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val themeRepo = FakeThemeRepository()
    private val emailBrandingRepo = FakeTenantEmailBrandingRepository()
    private val idpRepo = FakeIdentityProviderRepository()

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key")

    private val masterTenant =
        Tenant(
            id = TenantId(1),
            slug = "master",
            displayName = "Master",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private fun workspaceWith(
        smtpReady: Boolean = false,
        passwordEnabled: Boolean = true,
    ) = Tenant(
        id = TenantId(2),
        slug = "acme",
        displayName = "Acme Corp",
        issuerUrl = null,
        theme = TenantTheme.DEFAULT,
        smtpHost = if (smtpReady) "smtp.example.com" else null,
        smtpFromAddress = if (smtpReady) "no-reply@acme.dev" else null,
        smtpEnabled = smtpReady,
        securityConfig = SecurityConfig(passwordLoginEnabled = passwordEnabled),
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

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        auditLogPort.clear()
        themeRepo.clear()
        emailBrandingRepo.clear()
        idpRepo.clear()
        tenantRepo.add(masterTenant)
        userRepo.add(adminUser)
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

    private fun buildCredentialFlowService() =
        CredentialFlowService(
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
        AdminAccountService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            auditLog = auditLogPort,
            credentialFlowService = buildCredentialFlowService(),
        )

    private fun buildAdminUserService() =
        com.kauth.domain.service.AdminUserService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            credentialFlowService = buildCredentialFlowService(),
        )

    private fun buildWorkspaceSettingsService() =
        WorkspaceSettingsService(
            tenantRepository = tenantRepo,
            auditLog = auditLogPort,
            themeRepository = themeRepo,
            emailBrandingRepository = emailBrandingRepo,
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

    private fun buildSecurityMethodsService() =
        SecurityMethodsService(
            tenantRepository = tenantRepo,
            identityProviderRepository = idpRepo,
        )

    private suspend fun login(client: io.ktor.client.HttpClient) {
        client.submitForm(
            url = "/test-admin-login",
            formParameters = Parameters.build { },
        )
    }

    private fun io.ktor.server.application.Application.installTestApp(workspace: Tenant) {
        tenantRepo.add(workspace)
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            post("/test-admin-login") {
                call.sessions.set(
                    AdminSession(
                        userId = 1,
                        tenantId = 1,
                        username = "admin",
                    ),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            adminRoutes(
                accountService = buildAdminService(),
                workspaceSettingsService = buildWorkspaceSettingsService(),
                adminUserService = buildAdminUserService(),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = appRepo,
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = auditLogPort,
                    ),
                roleGroupService = buildRoleGroupService(),
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                encryptionService = encryptionService,
                roleRepository = roleRepo,
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    com.kauth.infrastructure.CachingClaimMapperService(
                        mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
                    ),
                securityMethodsService = buildSecurityMethodsService(),
            )
        }
    }

    @Test
    fun `GET sign-in-methods page returns 200 and renders method-table`() =
        testApplication {
            val ws = workspaceWith()
            application { installTestApp(ws) }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/sign-in-methods")

            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "method-table")
        }

    @Test
    fun `GET sign-in-methods page renders core method rows`() =
        testApplication {
            val ws = workspaceWith()
            application { installTestApp(ws) }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/settings/sign-in-methods").bodyAsText()

            assertContains(body, "Password")
            assertContains(body, "Passkey")
        }

    @Test
    fun `GET sign-in-methods page does not show social rows when no IDPs configured`() =
        testApplication {
            val ws = workspaceWith()
            application { installTestApp(ws) }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/settings/sign-in-methods").bodyAsText()

            assertTrue(!body.contains("OAuth credentials required"))
        }

    @Test
    fun `GET sign-in-methods page shows password-off warning when password login disabled`() =
        testApplication {
            val ws = workspaceWith(smtpReady = true, passwordEnabled = false)
            application { installTestApp(ws) }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/settings/sign-in-methods").bodyAsText()

            assertTrue(body.contains("Disabling passwords"))
        }

    @Test
    fun `GET security page still loads after sign-in-methods split`() =
        testApplication {
            val ws = workspaceWith()
            application { installTestApp(ws) }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            // Security Policy page no longer contains the grid — just verify it loads
            assertEquals(HttpStatusCode.OK, authed.get("/admin/workspaces/acme/settings/security").status)
        }
}
