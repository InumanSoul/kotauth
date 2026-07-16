package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.SecurityConfig
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
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.CachingClaimMapperService
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the v1.21.0 API key CRUD REST API:
 *   - GET    /api-keys
 *   - POST   /api-keys
 *   - DELETE /api-keys/{id}
 */
class ApiKeyManagementRoutesTest {
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
    private val webhookEndpointRepo = FakeWebhookEndpointRepository()
    private val webhookDeliveryRepo = FakeWebhookDeliveryRepository()

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = "https://acme.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(),
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

    private val webhookService =
        WebhookService(
            endpointRepository = webhookEndpointRepo,
            deliveryRepository = webhookDeliveryRepo,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

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
        webhookEndpointRepo.clear()
        webhookDeliveryRepo.clear()

        tenantRepo.add(tenant)

        rawApiKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Test Key",
                    scopes = listOf(ApiScope.API_KEYS_READ, ApiScope.API_KEYS_WRITE, ApiScope.WEBHOOKS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun apiKeyWithScopes(scopes: List<String>): String =
        (
            apiKeyService.create(
                tenantId = TenantId(1),
                name = "Limited Key",
                scopes = scopes,
            ) as ApiKeyResult.Success
        ).value.rawKey

    private fun createApiKeyBody(
        name: String = "CI pipeline",
        scopes: String = """["users:read", "users:write"]""",
        expiresAt: String? = null,
    ): String {
        val expiresField = expiresAt?.let { ""","expiresAt":"$it"""" } ?: ""
        return """{"name":"$name","scopes":$scopes$expiresField}"""
    }

    // -------------------------------------------------------------------------
    // GET /api-keys
    // -------------------------------------------------------------------------

    @Test
    fun `GET api-keys returns configured keys with prefix but no hash`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/api-keys") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(1, data.size)
            val dto = data[0].jsonObject
            assertEquals("Test Key", dto["name"]!!.jsonPrimitive.content)
            assertTrue(dto["keyPrefix"]!!.jsonPrimitive.content.startsWith("kauth_acme_"))
            assertTrue(dto["enabled"]!!.jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `GET api-keys does not expose keyHash field in response body`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/api-keys") { bearerAuth(rawApiKey) }

            assertFalse(response.bodyAsText().contains("keyHash", ignoreCase = true))
        }

    // -------------------------------------------------------------------------
    // POST /api-keys
    // -------------------------------------------------------------------------

    @Test
    fun `POST api-keys creates key and returns rawKey exactly once`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody())
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val newRawKey = body["rawKey"]!!.jsonPrimitive.content
            assertTrue(newRawKey.startsWith("kauth_acme_"))
            val dto = body["apiKey"]!!.jsonObject
            assertEquals("CI pipeline", dto["name"]!!.jsonPrimitive.content)

            // The following GET does not repeat the raw key.
            val listResponse = client.get("/t/acme/api/v1/api-keys") { bearerAuth(rawApiKey) }
            assertFalse(listResponse.bodyAsText().contains(newRawKey))
        }

    @Test
    fun `POST api-keys returns 422 for blank name`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody(name = ""))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST api-keys returns 422 for name over 128 chars`() =
        testApplication {
            application { installTestApp() }
            val longName = "a".repeat(129)

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody(name = longName))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST api-keys returns 422 for empty scopes`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody(scopes = "[]"))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST api-keys filters unknown scopes silently but 422s if none valid`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody(scopes = """["bogus:scope"]"""))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST api-keys accepts expiresAt ISO-8601`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody(expiresAt = "2027-01-01T00:00:00Z"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val dto = body["apiKey"]!!.jsonObject
            assertEquals("2027-01-01T00:00:00Z", dto["expiresAt"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST api-keys returns 422 for invalid expiresAt format`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody(expiresAt = "not-a-date"))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    // -------------------------------------------------------------------------
    // DELETE /api-keys/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `DELETE api-keys id returns 204 and flips enabled to false`() =
        testApplication {
            application { installTestApp() }
            val created =
                (
                    apiKeyService.create(
                        tenantId = TenantId(1),
                        name = "To revoke",
                        scopes = listOf(ApiScope.USERS_READ),
                    ) as ApiKeyResult.Success
                ).value.apiKey

            val response =
                client.delete("/t/acme/api/v1/api-keys/${created.id}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val stored = apiKeyRepo.findById(created.id!!, TenantId(1))
            assertFalse(stored!!.enabled)
        }

    @Test
    fun `DELETE api-keys id returns 404 for unknown id`() =
        testApplication {
            application { installTestApp() }

            val response = client.delete("/t/acme/api/v1/api-keys/999999") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `Meta-circular key can revoke itself`() =
        testApplication {
            application { installTestApp() }
            // Look up the id backing rawApiKey (the same key used to authenticate this call).
            val self = apiKeyRepo.all().single { it.name == "Test Key" }

            val response =
                client.delete("/t/acme/api/v1/api-keys/${self.id}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val stored = apiKeyRepo.findById(self.id!!, TenantId(1))
            assertFalse(stored!!.enabled)
        }

    // -------------------------------------------------------------------------
    // Scope enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `Scope enforcement without API_KEYS_WRITE gets 403 on POST`() =
        testApplication {
            application { installTestApp() }
            val readOnlyKey = apiKeyWithScopes(listOf(ApiScope.API_KEYS_READ))

            val response =
                client.post("/t/acme/api/v1/api-keys") {
                    bearerAuth(readOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody(createApiKeyBody())
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `Scope enforcement without API_KEYS_READ gets 403 on GET`() =
        testApplication {
            application { installTestApp() }
            val writeOnlyKey = apiKeyWithScopes(listOf(ApiScope.API_KEYS_WRITE))

            val response = client.get("/t/acme/api/v1/api-keys") { bearerAuth(writeOnlyKey) }

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
                webhookService = webhookService,
                resourceServerService = ResourceServerService(FakeResourceServerRepository()),
            )
        }
    }
}
