package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApiKeyRepository
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyResult
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
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.CachingClaimMapperService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
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
import kotlin.test.assertNull

/**
 * An API key mutation that does not happen must not be reported as done.
 *
 * Each of these routes looks the key up before writing, so the service's own failure branch is
 * only reachable when the key disappears between the two lookups — which
 * [VanishingApiKeyRepository] reproduces.
 */
class AdminApiKeyFailureReportingTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val backingApiKeyRepo = FakeApiKeyRepository()
    private val apiKeyRepo = VanishingApiKeyRepository(backingApiKeyRepo)
    private val hasher = FakePasswordHasher()

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key")

    private val apiKeyService =
        ApiKeyService(
            apiKeyRepository = apiKeyRepo,
            tenantRepository = tenantRepo,
        )

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
        backingApiKeyRepo.clear()
        auditLogPort.clear()
        apiKeyRepo.vanishAfterNextFind = false
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
    fun `a dialect update that fails does not redirect to the saved toast`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val created = apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))
            val key = (created as ApiKeyResult.Success).value.apiKey
            val target =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id
            apiKeyRepo.vanishAfterNextFind = true

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/scim-dialect",
                    formParameters = Parameters.build { append("scimDialect", target) },
                )

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNull(response.headers["Location"], "a failed update must not redirect to the success toast")
            assertNull(backingApiKeyRepo.findById(key.id!!, TenantId(2)), "nothing was written")
        }

    @Test
    fun `a revoke that fails does not redirect to the key list`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val created = apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))
            val key = (created as ApiKeyResult.Success).value.apiKey
            apiKeyRepo.vanishAfterNextFind = true

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/revoke",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNull(response.headers["Location"], "a failed revoke must not look like it worked")
        }

    @Test
    fun `a delete that fails does not redirect to the key list`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val created = apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))
            val key = (created as ApiKeyResult.Success).value.apiKey
            apiKeyRepo.vanishAfterNextFind = true

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/delete",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNull(response.headers["Location"], "a failed delete must not look like it worked")
        }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Drops the key after the next successful lookup, standing in for a concurrent delete. */
    private class VanishingApiKeyRepository(
        private val delegate: FakeApiKeyRepository,
    ) : ApiKeyRepository by delegate {
        var vanishAfterNextFind = false

        override fun findById(
            id: Int,
            tenantId: TenantId,
        ): ApiKey? =
            delegate.findById(id, tenantId)?.also {
                if (vanishAfterNextFind) {
                    vanishAfterNextFind = false
                    delegate.delete(id, tenantId)
                }
            }
    }

    private suspend fun login(client: HttpClient) {
        client.submitForm(url = "/test-admin-login", formParameters = Parameters.build { })
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
                        credentialFlowService = buildCredentialFlowService(),
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
                        credentialFlowService = buildCredentialFlowService(),
                    ),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = appRepo,
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = auditLogPort,
                    ),
                roleGroupService =
                    RoleGroupService(
                        roleRepository = roleRepo,
                        groupRepository = groupRepo,
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        applicationRepository = appRepo,
                        auditLog = auditLogPort,
                    ),
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                apiKeyService = apiKeyService,
                encryptionService = encryptionService,
                roleRepository = roleRepo,
                baseUrl = "https://auth.example.com",
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    CachingClaimMapperService(
                        mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
                    ),
            )
        }
    }
}
