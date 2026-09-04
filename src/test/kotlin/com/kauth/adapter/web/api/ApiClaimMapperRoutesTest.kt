package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.WebAuthnService
import com.kauth.domain.service.WebhookService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.assertTrue

/**
 * Integration tests for the claim-mapper REST API.
 */
class ApiClaimMapperRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val claimMapperRepo = FakeTenantClaimMapperRepository()
    private val hasher = FakePasswordHasher()

    private val apiKeyService =
        ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo)
    private val claimMapperService =
        CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private val tenant =
        Tenant(id = TenantId(1), slug = "acme", displayName = "Acme", issuerUrl = null, theme = TenantTheme.DEFAULT)

    private var readKey: String = ""
    private var writeKey: String = ""

    private fun buildFakeSelfService() =
        com.kauth.domain.service.CredentialFlowService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = com.kauth.fakes.FakeSessionRepository(),
            passwordHasher = hasher,
            auditLog = com.kauth.fakes.FakeAuditLogPort(),
            evTokenRepo = com.kauth.fakes.FakeEmailVerificationTokenRepository(),
            prTokenRepo = com.kauth.fakes.FakePasswordResetTokenRepository(),
            emailPort = com.kauth.fakes.FakeEmailPort(),
            emailScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        apiKeyRepo.clear()
        claimMapperRepo.clear()

        tenantRepo.add(tenant)
        // Dummy user needed to spin up the shared dependencies inside installTestApp().
        userRepo.add(
            User(
                id = UserId(1),
                tenantId = TenantId(1),
                username = "admin",
                email = "admin@acme.com",
                fullName = "Admin",
                passwordHash = hasher.hash("pw"),
            ),
        )

        readKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Read",
                    scopes = listOf(ApiScope.CLAIM_MAPPERS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
        writeKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Write",
                    scopes = listOf(ApiScope.CLAIM_MAPPERS_READ, ApiScope.CLAIM_MAPPERS_WRITE),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    // =========================================================================
    // GET /claim-mappers
    // =========================================================================

    @Test
    fun `GET mappers returns empty list when none configured`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.get("/t/acme/api/v1/claim-mappers") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"mappers\":[]"))
        }

    @Test
    fun `GET mappers returns configured mappers`() =
        testApplication {
            application { installTestApp() }
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(1), "plan", "custom:plan"))
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(1), "trial_ends", "custom:trial_ends"))

            val response =
                client.get("/t/acme/api/v1/claim-mappers") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"attributeKey\":\"plan\""))
            assertTrue(body.contains("\"claimName\":\"custom:plan\""))
            assertTrue(body.contains("\"attributeKey\":\"trial_ends\""))
        }

    @Test
    fun `GET mappers returns 403 when scope missing`() =
        testApplication {
            application { installTestApp() }
            val noScopeKey =
                (
                    apiKeyService.create(
                        tenantId = TenantId(1),
                        name = "No Scope",
                        scopes = listOf(ApiScope.USERS_READ),
                    ) as ApiKeyResult.Success
                ).value.rawKey

            val response =
                client.get("/t/acme/api/v1/claim-mappers") { bearerAuth(noScopeKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // =========================================================================
    // PUT /claim-mappers/{attributeKey}
    // =========================================================================

    @Test
    fun `PUT mapper creates new mapping`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.put("/t/acme/api/v1/claim-mappers/plan") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"claimName":"custom:plan","includeInAccess":true,"includeInId":true}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val stored = claimMapperRepo.findAll(TenantId(1)).single()
            assertEquals("plan", stored.attributeKey)
            assertEquals("custom:plan", stored.claimName)
            assertEquals(true, stored.includeInAccess)
            assertEquals(true, stored.includeInId)
        }

    @Test
    fun `PUT mapper overwrites existing mapping`() =
        testApplication {
            application { installTestApp() }
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(1), "plan", "custom:plan"))

            val response =
                client.put("/t/acme/api/v1/claim-mappers/plan") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"claimName":"custom:tier","includeInAccess":false,"includeInId":true}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val stored = claimMapperRepo.findAll(TenantId(1)).single { it.attributeKey == "plan" }
            assertEquals("custom:tier", stored.claimName)
        }

    @Test
    fun `PUT mapper returns 400 for reserved OIDC claim name`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.put("/t/acme/api/v1/claim-mappers/my_sub") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"claimName":"sub"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Reserved"))
            assertTrue(response.bodyAsText().contains("'sub'"))
        }

    @Test
    fun `PUT mapper returns 409 for duplicate claim name`() =
        testApplication {
            application { installTestApp() }
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(1), "plan", "custom:tier"))

            val response =
                client.put("/t/acme/api/v1/claim-mappers/subscription") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"claimName":"custom:tier"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `PUT mapper returns 403 when scope missing`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.put("/t/acme/api/v1/claim-mappers/plan") {
                    bearerAuth(readKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"claimName":"custom:plan"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `PUT mapper returns 409 when tenant cap reached`() =
        testApplication {
            application { installTestApp() }
            repeat(TenantClaimMapper.MAX_MAPPERS_PER_TENANT) { i ->
                claimMapperRepo.upsert(TenantClaimMapper(TenantId(1), "attr_$i", "custom:$i"))
            }

            val response =
                client.put("/t/acme/api/v1/claim-mappers/attr_overflow") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"claimName":"custom:overflow"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("Mapper Limit Reached"))
        }

    // =========================================================================
    // DELETE /claim-mappers/{attributeKey}
    // =========================================================================

    @Test
    fun `DELETE mapper removes it and returns 204`() =
        testApplication {
            application { installTestApp() }
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(1), "plan", "custom:plan"))

            val response =
                client.delete("/t/acme/api/v1/claim-mappers/plan") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(claimMapperRepo.findAll(TenantId(1)).isEmpty())
        }

    @Test
    fun `DELETE missing mapper still returns 204`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.delete("/t/acme/api/v1/claim-mappers/nonexistent") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE mapper returns 403 when scope missing`() =
        testApplication {
            application { installTestApp() }
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(1), "plan", "custom:plan"))

            val response =
                client.delete("/t/acme/api/v1/claim-mappers/plan") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // -------------------------------------------------------------------------
    // Test application wiring
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

        val userAttributeService =
            com.kauth.domain.service.UserAttributeService(
                userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                userRepository = userRepo,
            )

        routing {
            apiRoutes(
                apiKeyService = apiKeyService,
                tenantRepository = tenantRepo,
                roleRepository = com.kauth.fakes.FakeRoleRepository(),
                groupRepository = com.kauth.fakes.FakeGroupRepository(),
                applicationRepository = com.kauth.fakes.FakeApplicationRepository(),
                sessionRepository = com.kauth.fakes.FakeSessionRepository(),
                auditLogRepository = com.kauth.fakes.FakeAuditLogRepository(),
                roleGroupService =
                    com.kauth.domain.service.RoleGroupService(
                        roleRepository = com.kauth.fakes.FakeRoleRepository(),
                        groupRepository = com.kauth.fakes.FakeGroupRepository(),
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        applicationRepository = com.kauth.fakes.FakeApplicationRepository(),
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                    ),
                accountService =
                    com.kauth.domain.service.AdminAccountService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                        credentialFlowService = buildFakeSelfService(),
                    ),
                adminUserService =
                    com.kauth.domain.service.AdminUserService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        sessionRepository = com.kauth.fakes.FakeSessionRepository(),
                        passwordHasher = hasher,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                        credentialFlowService = buildFakeSelfService(),
                        collisionCheck =
                            com.kauth.domain.service
                                .IdentifierCollisionCheck(userRepo),
                        usernameGenerator =
                            com.kauth.domain.service
                                .UsernameGenerator(userRepo),
                    ),
                mfaService =
                    com.kauth.domain.service.MfaService(
                        mfaRepository = com.kauth.fakes.FakeMfaRepository(),
                        userRepository = userRepo,
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                    ),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = com.kauth.fakes.FakeApplicationRepository(),
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                    ),
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
                userRepository = userRepo,
                transactionRunner = com.kauth.fakes.FakeTransactionRunner(),
            )
        }
    }
}
