package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.WebAuthnService
import com.kauth.domain.service.WebhookService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeMfaRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the v1.21.0 group ↔ role assignment endpoints:
 *   - POST   /groups/{groupId}/roles/{roleRef}
 *   - DELETE /groups/{groupId}/roles/{roleRef}
 */
class ApiRbacRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val evTokenRepo = FakeEmailVerificationTokenRepository()
    private val prTokenRepo = FakePasswordResetTokenRepository()
    private val emailPort = FakeEmailPort()
    private val userAttributeRepo = FakeUserAttributeRepository()
    private val claimMapperRepo = FakeTenantClaimMapperRepository()
    private val mfaRepo = FakeMfaRepository()
    private val hasher = FakePasswordHasher()

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val otherTenant =
        Tenant(
            id = TenantId(2),
            slug = "globex",
            displayName = "Globex",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val apiKeyService = ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo)

    private val accountSelfService =
        CredentialFlowService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            evTokenRepo = evTokenRepo,
            prTokenRepo = prTokenRepo,
            emailPort = emailPort,
            emailScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

    private val adminService =
        AdminAccountService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            auditLog = auditLogPort,
            credentialFlowService = accountSelfService,
        )

    private val adminUserService =
        com.kauth.domain.service.AdminUserService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            credentialFlowService = accountSelfService,
        )

    private val mfaService =
        MfaService(
            mfaRepository = mfaRepo,
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
        )

    private val applicationManagementService =
        com.kauth.domain.service.ApplicationManagementService(
            applicationRepository = appRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
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

    private val userAttributeService =
        com.kauth.domain.service.UserAttributeService(
            userAttributeRepository = userAttributeRepo,
            userRepository = userRepo,
        )

    private val claimMapperService =
        CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private var rawApiKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        apiKeyRepo.clear()
        auditLogRepo.clear()
        auditLogPort.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        emailPort.clear()
        sessionRepo.clear()
        mfaRepo.clear()
        roleRepo.clear()
        groupRepo.clear()

        tenantRepo.add(tenant)
        tenantRepo.add(otherTenant)

        rawApiKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Test Key",
                    scopes = listOf(ApiScope.ROLES_WRITE, ApiScope.ROLES_READ, ApiScope.GROUPS_WRITE),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun seedGroup(
        tenantId: TenantId = TenantId(1),
        name: String = "engineering",
    ): Group = groupRepo.add(Group(tenantId = tenantId, name = name))

    private fun seedRole(
        tenantId: TenantId = TenantId(1),
        name: String = "editor",
        scope: RoleScope = RoleScope.TENANT,
    ): Role = roleRepo.add(Role(tenantId = tenantId, name = name, scope = scope))

    private fun apiKeyMissingScope(scopes: List<String>): String =
        (
            apiKeyService.create(
                tenantId = TenantId(1),
                name = "Limited Key",
                scopes = scopes,
            ) as ApiKeyResult.Success
        ).value.rawKey

    // =========================================================================
    // POST /groups/{groupId}/roles/{roleRef}
    // =========================================================================

    @Test
    fun `POST groups roles returns 204 and assigns by numeric role id`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()
            val role = seedRole()

            val response =
                client.post("/t/acme/api/v1/groups/${group.id!!.value}/roles/${role.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(groupRepo.findRoleIdsForGroup(group.id).contains(role.id))
        }

    @Test
    fun `POST groups roles returns 204 and assigns by role name`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()
            val role = seedRole(name = "billing-admin")

            val response =
                client.post("/t/acme/api/v1/groups/${group.id!!.value}/roles/billing-admin") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(groupRepo.findRoleIdsForGroup(group.id).contains(role.id))
        }

    @Test
    fun `POST groups roles returns 404 for unknown group id`() =
        testApplication {
            application { installTestApp() }
            val role = seedRole()

            val response =
                client.post("/t/acme/api/v1/groups/99999/roles/${role.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `POST groups roles returns 404 for unknown role name`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()

            val response =
                client.post("/t/acme/api/v1/groups/${group.id!!.value}/roles/does-not-exist") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `POST groups roles is rejected when group belongs to a different tenant`() =
        testApplication {
            application { installTestApp() }
            // Group lives in tenant 2; the request is authenticated against tenant 1's API key.
            val foreignGroup = seedGroup(tenantId = TenantId(2))
            val role = seedRole(tenantId = TenantId(1))

            val response =
                client.post("/t/acme/api/v1/groups/${foreignGroup.id!!.value}/roles/${role.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            // RoleGroupService.assignRoleToGroup treats a group/role tenant mismatch as a
            // validation error (422), not a not-found (404) — unlike unassignRoleFromGroup,
            // which returns 404 for the same mismatch. See RoleGroupService.kt:579-581.
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertFalse(groupRepo.findRoleIdsForGroup(foreignGroup.id).contains(role.id))
        }

    @Test
    fun `POST groups roles returns 422 when role name is ambiguous`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()
            seedRole(name = "reviewer", scope = RoleScope.TENANT)
            seedRole(name = "reviewer", scope = RoleScope.CLIENT)

            val response =
                client.post("/t/acme/api/v1/groups/${group.id!!.value}/roles/reviewer") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST groups roles is idempotent — second call also 204`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()
            val role = seedRole()

            val first =
                client.post("/t/acme/api/v1/groups/${group.id!!.value}/roles/${role.id!!.value}") {
                    bearerAuth(rawApiKey)
                }
            val second =
                client.post("/t/acme/api/v1/groups/${group.id.value}/roles/${role.id.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, first.status)
            assertEquals(HttpStatusCode.NoContent, second.status)
            assertEquals(1, groupRepo.findRoleIdsForGroup(group.id).count { it == role.id })
        }

    // =========================================================================
    // DELETE /groups/{groupId}/roles/{roleRef}
    // =========================================================================

    @Test
    fun `DELETE groups roles returns 204 and removes assignment`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()
            val role = seedRole()
            groupRepo.assignRoleToGroup(group.id!!, role.id!!)

            val response =
                client.delete("/t/acme/api/v1/groups/${group.id.value}/roles/${role.id.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertFalse(groupRepo.findRoleIdsForGroup(group.id).contains(role.id))
        }

    @Test
    fun `DELETE groups roles returns 404 for unknown group`() =
        testApplication {
            application { installTestApp() }
            val role = seedRole()

            val response =
                client.delete("/t/acme/api/v1/groups/99999/roles/${role.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `DELETE groups roles is idempotent — removing unassigned role also 204`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()
            val role = seedRole()

            val response =
                client.delete("/t/acme/api/v1/groups/${group.id!!.value}/roles/${role.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    // =========================================================================
    // Scope enforcement
    // =========================================================================

    @Test
    fun `scope enforcement — key without ROLES_WRITE gets 403`() =
        testApplication {
            application { installTestApp() }
            val group = seedGroup()
            val role = seedRole()
            val limitedKey = apiKeyMissingScope(listOf(ApiScope.ROLES_READ, ApiScope.GROUPS_WRITE))

            val response =
                client.post("/t/acme/api/v1/groups/${group.id!!.value}/roles/${role.id!!.value}") {
                    bearerAuth(limitedKey)
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // -------------------------------------------------------------------------
    // Test wiring
    // -------------------------------------------------------------------------

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            bearer("api-key") {
                realm = "KotAuth REST API"
                authenticate { creds ->
                    if (creds.token.startsWith("kauth_")) ApiKeyPrincipal(rawToken = creds.token) else null
                }
            }
        }
        routing {
            apiRoutes(
                apiKeyService = apiKeyService,
                tenantRepository = tenantRepo,
                roleRepository = roleRepo,
                groupRepository = groupRepo,
                applicationRepository = appRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                roleGroupService = roleGroupService,
                accountService = adminService,
                adminUserService = adminUserService,
                mfaService = mfaService,
                applicationManagementService = applicationManagementService,
                userAttributeService = userAttributeService,
                claimMapperService = claimMapperService,
                emailOtpService = stubEmailOtpService(),
                otpEmailRateLimiter = AlwaysAllowLimiter(),
                otpIpRateLimiter = AlwaysAllowLimiter(),
                apiWriteRateLimiter = AlwaysAllowLimiter(),
                apiReadRateLimiter = AlwaysAllowLimiter(),
                webhookService = WebhookService(FakeWebhookEndpointRepository(), FakeWebhookDeliveryRepository()),
                resourceServerService = ResourceServerService(FakeResourceServerRepository()),
                webAuthnService =
                    WebAuthnService(
                        credentialRepository = FakeWebAuthnCredentialRepository(),
                        relyingParty = FakeRelyingPartyAdapter(),
                        secretKey = "test-secret-key-32chars-long-xxxx",
                        auditLog = FakeAuditLogPort(),
                        userRepository = FakeUserRepository(),
                    ),
                webAuthnCredentialRepository = FakeWebAuthnCredentialRepository(),
                transactionRunner = com.kauth.fakes.FakeTransactionRunner(),
            )
        }
    }
}
