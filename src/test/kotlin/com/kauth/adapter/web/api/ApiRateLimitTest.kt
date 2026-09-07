package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.CredentialFlowService
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
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the API write rate limiter (v1.21.0) — every
 * POST/PUT/PATCH/DELETE under `/t/{tenantSlug}/api/v1` (any subpath) is gated by a
 * per-(API key x tenant) [InMemoryRateLimiter]. GET is always exempt.
 *
 * Uses a deliberately low `maxRequests = 2` limiter so the third write in a
 * test trips the 429 without slow real-time waiting. Because each test gets a
 * freshly generated API key (and therefore a fresh `keyPrefix`) from
 * [ApiKeyService.create] in [setup], the buckets in the shared limiter never
 * collide across tests.
 */
class ApiRateLimitTest {
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
            collisionCheck =
                com.kauth.domain.service
                    .IdentifierCollisionCheck(userRepo),
            usernameGenerator =
                com.kauth.domain.service
                    .UsernameGenerator(userRepo),
        )

    private val mfaService =
        com.kauth.domain.service.MfaService(
            mfaRepository = com.kauth.fakes.FakeMfaRepository(),
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

    private val claimMapperService = CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    /** Very-low-limit limiter shared across tests; safe because each test's API key gets a fresh prefix. */
    private val apiWriteRateLimiter = InMemoryRateLimiter(maxRequests = 2, windowSeconds = 60)

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

        tenantRepo.add(tenant)

        rawApiKey = createKeyFor(TenantId(1))
    }

    private fun createKeyFor(tenantId: TenantId): String =
        (
            apiKeyService.create(
                tenantId = tenantId,
                name = "Test Key",
                scopes = listOf(ApiScope.USERS_WRITE, ApiScope.USERS_READ),
            ) as ApiKeyResult.Success
        ).value.rawKey

    private fun createUserBody(username: String) =
        """{"username":"$username","email":"$username@acme.com","fullName":"$username","password":"Password123!"}"""

    // =========================================================================
    // 1-3: basic allow / block / shape
    // =========================================================================

    @Test
    fun `first two POST users write requests succeed`() =
        testApplication {
            application { installTestApp() }

            val first =
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("alice"))
                }
            val second =
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("bob"))
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
        }

    @Test
    fun `third POST users write request returns 429 with Retry-After header`() =
        testApplication {
            application { installTestApp() }

            repeat(2) { i ->
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("user$i"))
                }
            }

            val third =
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("user2"))
                }

            assertEquals(HttpStatusCode.TooManyRequests, third.status)
            assertTrue(third.headers.contains("Retry-After"))
        }

    @Test
    fun `429 response is application problem+json with clear detail message`() =
        testApplication {
            application { installTestApp() }

            repeat(2) { i ->
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("dup$i"))
                }
            }

            val response =
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("dup2"))
                }

            assertEquals("application/problem+json", response.headers["Content-Type"])
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(429, body["status"]!!.jsonPrimitive.int)
            assertEquals("Rate limit exceeded", body["title"]!!.jsonPrimitive.content)
            assertTrue(body["detail"]!!.jsonPrimitive.content.contains("rate limit", ignoreCase = true))
        }

    // =========================================================================
    // 4: GET is exempt
    // =========================================================================

    @Test
    fun `GET requests never trigger the rate limiter — 100 GETs succeed`() =
        testApplication {
            application { installTestApp() }

            repeat(100) {
                val response =
                    client.get("/t/acme/api/v1/users") {
                        bearerAuth(rawApiKey)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    // =========================================================================
    // 5: scoped per API key
    // =========================================================================

    @Test
    fun `rate limit is scoped per API key — a second key hitting the same tenant is not rate-limited`() =
        testApplication {
            application { installTestApp() }

            // Exhaust the first key's bucket.
            repeat(2) { i ->
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("first$i"))
                }
            }
            val blocked =
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("first2"))
                }
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)

            // A second, distinct key (different keyPrefix) for the same tenant is unaffected.
            val secondKey = createKeyFor(TenantId(1))
            val allowed =
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(secondKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("second0"))
                }
            assertEquals(HttpStatusCode.Created, allowed.status)
        }

    // Note: a "scoped per tenant" test (#6 in the brief) is skipped — ApiKeyService.validate()
    // binds every key to exactly one tenantId (validate() returns null on a tenant mismatch), so
    // the same raw key can never authenticate against a second tenant's slug. There is no way to
    // exercise "same key, different tenant" without it being identical in effect to the per-key
    // test above (a distinct key is always required for a distinct tenant).

    // =========================================================================
    // 7: DELETE counts too
    // =========================================================================

    @Test
    fun `DELETE also counts against the write limit`() =
        testApplication {
            application { installTestApp() }
            val user =
                userRepo.add(
                    com.kauth.domain.model.User(
                        tenantId = TenantId(1),
                        username = "target",
                        email = "target@acme.com",
                        fullName = "Target",
                        passwordHash = hasher.hash("pass"),
                    ),
                )
            val userId = user.id!!.value

            repeat(2) {
                client.delete("/t/acme/api/v1/users/$userId/mfa/reset") {
                    bearerAuth(rawApiKey)
                }
            }
            val third =
                client.delete("/t/acme/api/v1/users/$userId/mfa/reset") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.TooManyRequests, third.status)
        }

    // Note: #8 (PATCH also counts) is skipped — grep confirms no PATCH route exists anywhere
    // under src/main/kotlin/com/kauth/adapter/web/api/ in v1.21.0.

    // =========================================================================
    // 9: Retry-After matches windowSeconds
    // =========================================================================

    @Test
    fun `Retry-After header matches the limiter's windowSeconds`() =
        testApplication {
            application { installTestApp() }

            repeat(2) { i ->
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("ra$i"))
                }
            }
            val blocked =
                client.post("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createUserBody("ra2"))
                }

            assertEquals(apiWriteRateLimiter.windowSeconds.toString(), blocked.headers["Retry-After"])
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
                apiWriteRateLimiter = apiWriteRateLimiter,
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
