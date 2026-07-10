package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.WorkspaceSettingsService
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
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeTokenPort
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the admin sidebar nav structure after the v1.20.1 Security rail restructure.
 *
 * Checks that:
 * - The Security ctx-panel contains the correct entries in the correct order.
 * - The Settings ctx-panel no longer lists Security Policy.
 * - The Security rail landing URL points to /settings/security.
 */
class AdminSidebarTest {
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
    private val themeRepo = FakeThemeRepository()
    private val emailBrandingRepo = FakeTenantEmailBrandingRepository()
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

    private val workspace =
        Tenant(
            id = TenantId(2),
            slug = "acme",
            displayName = "Acme Corp",
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

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        auditLogPort.clear()
        themeRepo.clear()
        emailBrandingRepo.clear()
        tokenPort.reset()
        tenantRepo.add(masterTenant)
        tenantRepo.add(workspace)
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

    @Test
    fun `security ctx-panel lists Security Policy, MFA, Passkeys, Sessions in order`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val html = authed.get("/admin/workspaces/acme/settings/security").bodyAsText()

            val secIdx = html.indexOf("Security Policy")
            val mfaIdx = html.indexOf(">MFA<")
            val passkeysIdx = html.indexOf(">Passkeys<")
            val sessionsIdx = html.indexOf(">Sessions<")

            assertTrue(secIdx > 0, "Security Policy link not found in sidebar")
            assertTrue(mfaIdx > secIdx, "MFA must appear after Security Policy")
            assertTrue(passkeysIdx > mfaIdx, "Passkeys must appear after MFA")
            assertTrue(sessionsIdx > passkeysIdx, "Sessions must appear after Passkeys")
        }

    @Test
    fun `settings ctx-panel no longer lists Security Policy`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            // /settings is the General settings page; it renders the Settings ctx-panel
            val html = authed.get("/admin/workspaces/acme/settings").bodyAsText()

            // Extract the sidebar content and confirm Security Policy is absent.
            // The Settings panel heading appears before the closing </aside> tag.
            val panelStart = html.indexOf("sidebar__heading")
            assertTrue(panelStart > 0, "sidebar heading not found")
            val asideClose = html.indexOf("</aside>", panelStart)
            val settingsPanel =
                if (asideClose >
                    0
                ) {
                    html.substring(panelStart, asideClose)
                } else {
                    html.substring(panelStart)
                }
            assertFalse(
                settingsPanel.contains("Security Policy"),
                "Security Policy must not appear in Settings sidebar",
            )
        }

    @Test
    fun `security rail links to settings-security not sessions`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val html = authed.get("/admin/workspaces/acme/mfa").bodyAsText()

            assertTrue(
                html.contains("href=\"/admin/workspaces/acme/settings/security\""),
                "Security rail item must point to /settings/security",
            )
        }

    @Test
    fun `GET settings passkeys returns 200`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/passkeys")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `passkeys page renders enrollment insight and security policy link`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val html = authed.get("/admin/workspaces/acme/settings/passkeys").bodyAsText()

            assertTrue(html.contains("Passkey enrollment"), "Enrollment insight label missing")
            assertTrue(html.contains("Open Security Policy"), "Security Policy link missing")
        }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private suspend fun login(client: io.ktor.client.HttpClient) {
        client.submitForm(
            url = "/test-admin-login",
            formParameters = Parameters.build { },
        )
    }

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            post("/test-admin-login") {
                call.sessions.set(AdminSession(userId = 1, tenantId = 1, username = "admin"))
                call.respond(HttpStatusCode.OK, "session set")
            }
            adminRoutes(
                accountService = buildAdminService(),
                workspaceSettingsService = buildWorkspaceSettingsService(),
                adminUserService = buildAdminUserService(),
                applicationManagementService = buildAppMgmtService(),
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
            )
        }
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

    private fun buildAppMgmtService() =
        com.kauth.domain.service.ApplicationManagementService(
            applicationRepository = appRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
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
}
