package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.RoleGroupService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminResourceServerRoutesTest {
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
    private val fakeResourceServerRepo = FakeResourceServerRepository()

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key")

    private val tenantId = TenantId(2)
    private val tenantSlug = "acme"

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
            id = tenantId,
            slug = tenantSlug,
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
            collisionCheck =
                com.kauth.domain.service
                    .IdentifierCollisionCheck(userRepo),
            usernameGenerator =
                com.kauth.domain.service
                    .UsernameGenerator(userRepo),
        )

    private fun buildAppMgmtService() =
        com.kauth.domain.service.ApplicationManagementService(
            applicationRepository = appRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
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
        fakeResourceServerRepo.clear()
        auditLogPort.clear()
        tokenPort.reset()
        tenantRepo.add(masterTenant)
        tenantRepo.add(workspace)
        userRepo.add(adminUser)
        val adminRole =
            roleRepo.add(
                Role(
                    tenantId = TenantId(1),
                    name = "admin",
                    scope = RoleScope.TENANT,
                ),
            )
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)
    }

    // =========================================================================
    // POST /apis — create
    // =========================================================================

    @Test
    fun `POST settings apis create persists scopes from newline-separated textarea`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/$tenantSlug/apis",
                    formParameters =
                        Parameters.build {
                            append("identifier", "https://api.example.com")
                            append("name", "Example API")
                            append("scopes", "read:invoices\nwrite:invoices\n")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = fakeResourceServerRepo.findByIdentifier(tenantId, "https://api.example.com")
            assertEquals(listOf("read:invoices", "write:invoices"), stored?.scopes)
        }

    @Test
    fun `POST settings apis create with no scopes stores empty list`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            authed.submitForm(
                url = "/admin/workspaces/$tenantSlug/apis",
                formParameters =
                    Parameters.build {
                        append("identifier", "https://api.example.com")
                        append("name", "Example API")
                    },
            )

            val stored = fakeResourceServerRepo.findByIdentifier(tenantId, "https://api.example.com")
            assertEquals(emptyList(), stored?.scopes)
        }

    // =========================================================================
    // POST /apis/{id} — update
    // =========================================================================

    @Test
    fun `POST settings apis id update modifies scopes`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val existing =
                fakeResourceServerRepo.seed(
                    ResourceServer(
                        id = ResourceServerId(1),
                        tenantId = tenantId,
                        identifier = "https://api.example.com",
                        name = "Example API",
                        scopes = listOf("read:orders"),
                        enabled = true,
                        createdAt = Instant.now(),
                    ),
                )

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/$tenantSlug/apis/${existing.id!!.value}",
                    formParameters =
                        Parameters.build {
                            append("name", "Example API")
                            append("scopes", "read:invoices\nwrite:invoices")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = fakeResourceServerRepo.findByIdentifier(tenantId, "https://api.example.com")
            assertEquals(listOf("read:invoices", "write:invoices"), stored?.scopes)
        }

    // =========================================================================
    // GET /apis — list page renders scope badges
    // =========================================================================

    @Test
    fun `GET settings apis list renders scope badges`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            fakeResourceServerRepo.seed(
                ResourceServer(
                    id = ResourceServerId(1),
                    tenantId = tenantId,
                    identifier = "https://api.example.com",
                    name = "Example API",
                    scopes = listOf("read:invoices", "write:invoices"),
                    enabled = true,
                    createdAt = Instant.now(),
                ),
            )

            val response = authed.get("/admin/workspaces/$tenantSlug/apis")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("read:invoices"), "List page must render scope badges")
            assertTrue(body.contains("write:invoices"), "List page must render all scope badges")
            assertTrue(body.contains("badge--muted"), "Scope badges must use badge--muted class")
        }

    // =========================================================================
    // Helpers
    // =========================================================================

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
                workspaceSettingsService =
                    com.kauth.domain.service
                        .WorkspaceSettingsService(tenantRepo, auditLogPort),
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
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    com.kauth.infrastructure.CachingClaimMapperService(
                        mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
                    ),
                resourceServerService = ResourceServerService(fakeResourceServerRepo),
            )
        }
    }
}
