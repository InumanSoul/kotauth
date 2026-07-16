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
 * Integration tests for the v1.21.0 webhook CRUD REST API:
 *   - GET    /webhooks
 *   - POST   /webhooks
 *   - DELETE /webhooks/{endpointId}
 */
class ApiWebhookRoutesTest {
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
                    scopes = listOf(ApiScope.WEBHOOKS_READ, ApiScope.WEBHOOKS_WRITE),
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

    private fun createWebhookBody(
        url: String = "https://example.com/hooks/kotauth",
        description: String = "My integration",
        events: String = """["user.created", "login.success"]""",
    ) = """{"url":"$url","description":"$description","events":$events}"""

    // -------------------------------------------------------------------------
    // GET /webhooks
    // -------------------------------------------------------------------------

    @Test
    fun `GET webhooks returns empty envelope when no endpoints`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/webhooks") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["data"]!!.jsonArray.isEmpty())
            assertEquals(
                0,
                body["meta"]!!
                    .jsonObject["total"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `GET webhooks returns configured endpoints without exposing the secret`() =
        testApplication {
            application { installTestApp() }
            webhookEndpointRepo.add(
                com.kauth.domain.model.WebhookEndpoint(
                    tenantId = TenantId(1),
                    url = "https://example.com/hooks/kotauth",
                    secret = "super-secret-hmac-key",
                    events = setOf(com.kauth.domain.model.WebhookEventType.USER_CREATED),
                    description = "My integration",
                ),
            )

            val response = client.get("/t/acme/api/v1/webhooks") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val rawBody = response.bodyAsText()
            assertFalse(rawBody.contains("super-secret-hmac-key"))
            val body = jsonCodec.parseToJsonElement(rawBody).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(1, data.size)
            val dto = data[0].jsonObject
            assertEquals("https://example.com/hooks/kotauth", dto["url"]!!.jsonPrimitive.content)
            assertEquals("My integration", dto["description"]!!.jsonPrimitive.content)
            assertTrue(dto["enabled"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(listOf("user.created"), dto["events"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    // -------------------------------------------------------------------------
    // POST /webhooks
    // -------------------------------------------------------------------------

    @Test
    fun `POST webhooks creates an endpoint and returns plaintext secret one-time`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/webhooks") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createWebhookBody())
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val secret = body["secret"]!!.jsonPrimitive.content
            assertTrue(secret.isNotBlank())
            val endpoint = body["endpoint"]!!.jsonObject
            assertEquals("https://example.com/hooks/kotauth", endpoint["url"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("login.success", "user.created"),
                endpoint["events"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            // Persisted, and the following GET does not repeat the secret.
            val listResponse = client.get("/t/acme/api/v1/webhooks") { bearerAuth(rawApiKey) }
            assertFalse(listResponse.bodyAsText().contains(secret))
        }

    @Test
    fun `POST webhooks returns 422 for blank URL`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/webhooks") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createWebhookBody(url = ""))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST webhooks returns 422 for URL without http scheme`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/webhooks") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createWebhookBody(url = "ftp://example.com/hooks"))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST webhooks returns 422 for URL longer than 2048 chars`() =
        testApplication {
            application { installTestApp() }
            val longUrl = "https://example.com/" + "a".repeat(2048)

            val response =
                client.post("/t/acme/api/v1/webhooks") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createWebhookBody(url = longUrl))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST webhooks returns 422 for unknown event names with clear message`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/webhooks") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createWebhookBody(events = """["user.created", "bogus.event"]"""))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["detail"]!!.jsonPrimitive.content.contains("bogus.event"))
        }

    @Test
    fun `POST webhooks accepts multiple valid events`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/webhooks") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        createWebhookBody(
                            events = """["user.created", "user.updated", "user.deleted", "session.revoked"]""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val events = body["endpoint"]!!.jsonObject["events"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                listOf("session.revoked", "user.created", "user.deleted", "user.updated"),
                events,
            )
        }

    // -------------------------------------------------------------------------
    // DELETE /webhooks/{endpointId}
    // -------------------------------------------------------------------------

    @Test
    fun `DELETE webhooks id returns 204`() =
        testApplication {
            application { installTestApp() }
            val endpoint =
                webhookEndpointRepo.add(
                    com.kauth.domain.model.WebhookEndpoint(
                        tenantId = TenantId(1),
                        url = "https://example.com/hooks/kotauth",
                        secret = "hmac-key",
                        events = emptySet(),
                    ),
                )

            val response =
                client.delete("/t/acme/api/v1/webhooks/${endpoint.id}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(webhookEndpointRepo.findByTenantId(TenantId(1)).isEmpty())
        }

    @Test
    fun `DELETE webhooks id is idempotent for unknown id`() =
        testApplication {
            application { installTestApp() }

            val response = client.delete("/t/acme/api/v1/webhooks/999999") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    // -------------------------------------------------------------------------
    // Secret leakage + scope enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `Response DTO for GET webhooks does not contain secret field anywhere in the body`() =
        testApplication {
            application { installTestApp() }
            webhookEndpointRepo.add(
                com.kauth.domain.model.WebhookEndpoint(
                    tenantId = TenantId(1),
                    url = "https://example.com/hooks/kotauth",
                    secret = "hmac-key-value",
                    events = setOf(com.kauth.domain.model.WebhookEventType.LOGIN_FAILED),
                ),
            )

            val response = client.get("/t/acme/api/v1/webhooks") { bearerAuth(rawApiKey) }

            assertFalse(response.bodyAsText().contains("secret", ignoreCase = true))
        }

    @Test
    fun `Scope enforcement key without webhooks_write gets 403 on POST`() =
        testApplication {
            application { installTestApp() }
            val readOnlyKey = apiKeyWithScopes(listOf(ApiScope.WEBHOOKS_READ))

            val response =
                client.post("/t/acme/api/v1/webhooks") {
                    bearerAuth(readOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody(createWebhookBody())
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `Scope enforcement key without webhooks_read gets 403 on GET`() =
        testApplication {
            application { installTestApp() }
            val writeOnlyKey = apiKeyWithScopes(listOf(ApiScope.WEBHOOKS_WRITE))

            val response = client.get("/t/acme/api/v1/webhooks") { bearerAuth(writeOnlyKey) }

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
            )
        }
    }
}
