package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration tests for the v1.21.0 `GET /sessions` filtering extension:
 * `user_id`, `application_id`, `active_only`, `limit`, `offset` query params.
 */
class ApiSessionAuditRoutesTest {
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
            emailScope = CoroutineScope(Dispatchers.Unconfined),
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

    private val jsonCodec = Json { ignoreUnknownKeys = true }

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

        tenantRepo.add(tenant)

        rawApiKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Test Key",
                    scopes = listOf(ApiScope.SESSIONS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun seedUser(username: String = "alice"): User =
        userRepo.add(
            User(
                tenantId = TenantId(1),
                username = username,
                email = "$username@acme.com",
                fullName = username,
                passwordHash = hasher.hash("pass"),
                enabled = true,
            ),
        )

    private fun seedSession(
        userId: UserId? = null,
        clientId: ApplicationId? = null,
    ): Session =
        sessionRepo.save(
            Session(
                tenantId = TenantId(1),
                userId = userId,
                clientId = clientId,
                scopes = "openid",
                accessTokenHash = "hash-${System.nanoTime()}",
                refreshTokenHash = null,
                ipAddress = "127.0.0.1",
                createdAt = Instant.now(),
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )

    // =========================================================================
    // GET /sessions
    // =========================================================================

    @Test
    fun `GET sessions returns all active sessions when no filters`() =
        testApplication {
            application { installTestApp() }
            val alice = seedUser("alice")
            val bob = seedUser("bob")
            seedSession(userId = alice.id)
            seedSession(userId = bob.id)

            val response = client.get("/t/acme/api/v1/sessions") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["data"]!!.jsonArray.size)
            assertEquals(2, body["meta"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET sessions filters by user_id`() =
        testApplication {
            application { installTestApp() }
            val alice = seedUser("alice")
            val bob = seedUser("bob")
            seedSession(userId = alice.id)
            seedSession(userId = bob.id)

            val response =
                client.get("/t/acme/api/v1/sessions?user_id=${alice.id!!.value}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(1, data.size)
            assertEquals(alice.id!!.value, data[0].jsonObject["userId"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET sessions filters by application_id`() =
        testApplication {
            application { installTestApp() }
            val alice = seedUser("alice")
            val appA = ApplicationId(10)
            val appB = ApplicationId(20)
            seedSession(userId = alice.id, clientId = appA)
            seedSession(userId = alice.id, clientId = appB)

            val response =
                client.get("/t/acme/api/v1/sessions?application_id=${appA.value}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(1, data.size)
            assertEquals(appA.value, data[0].jsonObject["clientId"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET sessions combined user_id + application_id filter`() =
        testApplication {
            application { installTestApp() }
            val alice = seedUser("alice")
            val bob = seedUser("bob")
            val appA = ApplicationId(10)
            val appB = ApplicationId(20)
            seedSession(userId = alice.id, clientId = appA)
            seedSession(userId = alice.id, clientId = appB)
            seedSession(userId = bob.id, clientId = appA)

            val response =
                client.get(
                    "/t/acme/api/v1/sessions?user_id=${alice.id!!.value}&application_id=${appA.value}",
                ) { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["data"]!!.jsonArray.size)
        }

    @Test
    fun `GET sessions returns 400 when active_only=false`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.get("/t/acme/api/v1/sessions?active_only=false") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET sessions pagination limit=200 cap applied`() =
        testApplication {
            application { installTestApp() }
            val alice = seedUser("alice")
            repeat(5) { seedSession(userId = alice.id) }

            val response =
                client.get("/t/acme/api/v1/sessions?limit=1000") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(200, body["meta"]!!.jsonObject["limit"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET sessions offset skips first N`() =
        testApplication {
            application { installTestApp() }
            val alice = seedUser("alice")
            repeat(3) { seedSession(userId = alice.id) }

            val response =
                client.get("/t/acme/api/v1/sessions?offset=2") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["data"]!!.jsonArray.size)
            assertEquals(3, body["meta"]!!.jsonObject["total"]!!.jsonPrimitive.int)
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
