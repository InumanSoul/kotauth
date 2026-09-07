package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.RoleGroupService
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
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.CachingClaimMapperService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The admin UI's group-delete branches. The refusal branch re-renders the whole group detail page
 * from a session it reads with `!!`, so it is reachable only through the route — a service-level
 * test of `deleteGroup` proves nothing about it.
 */
class AdminGroupDeleteTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val hasher = FakePasswordHasher()

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

    private val roleGroupService =
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
        groupRepo.clear()
        apiKeyRepo.clear()
        auditLogPort.clear()
        tenantRepo.add(masterTenant)
        tenantRepo.add(workspace)
        userRepo.add(adminUser)
        val adminRole = roleRepo.add(Role(tenantId = TenantId(1), name = "admin", scope = RoleScope.TENANT))
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)
    }

    @Test
    fun `POST delete on a group with subgroups re-renders the detail page with the reason`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val parent = groupRepo.add(Group(tenantId = workspace.id, name = "Engineering"))
            val child =
                groupRepo.add(Group(tenantId = workspace.id, name = "Backend", parentGroupId = parent.id))

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/groups/${parent.id!!.value}/delete",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Backend"), "the blocking subgroup must be named")
            assertTrue(body.contains("reparent"), "the operator must be told how to clear the block")
            assertEquals(emptyList(), groupRepo.deleteCalls)
            assertNotNull(groupRepo.findById(parent.id))
            assertNotNull(groupRepo.findById(child.id!!))
        }

    @Test
    fun `POST delete on a leaf group redirects back to the group list`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val group = groupRepo.add(Group(tenantId = workspace.id, name = "Engineering"))

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/groups/${group.id!!.value}/delete",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("/admin/workspaces/acme/groups", response.headers["Location"])
            assertEquals(listOf(group.id), groupRepo.deleteCalls)
        }

    private suspend fun login(client: HttpClient) {
        client.submitForm(url = "/test-admin-login", formParameters = Parameters.build { })
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
                accountService =
                    AdminAccountService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        auditLog = auditLogPort,
                        credentialFlowService = credentialFlowService(),
                    ),
                workspaceSettingsService =
                    com.kauth.domain.service
                        .WorkspaceSettingsService(tenantRepo, auditLogPort),
                adminUserService =
                    com.kauth.domain.service.AdminUserService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        sessionRepository = sessionRepo,
                        passwordHasher = hasher,
                        auditLog = auditLogPort,
                        credentialFlowService = credentialFlowService(),
                        collisionCheck =
                            com.kauth.domain.service
                                .IdentifierCollisionCheck(userRepo),
                        usernameGenerator =
                            com.kauth.domain.service
                                .UsernameGenerator(userRepo),
                    ),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = appRepo,
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = auditLogPort,
                    ),
                roleGroupService = roleGroupService,
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                apiKeyService = ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo),
                encryptionService = encryptionService,
                roleRepository = roleRepo,
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    CachingClaimMapperService(mapperRepository = FakeTenantClaimMapperRepository()),
            )
        }
    }

    private fun credentialFlowService() =
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
}
