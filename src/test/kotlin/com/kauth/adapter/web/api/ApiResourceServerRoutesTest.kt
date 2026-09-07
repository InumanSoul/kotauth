package com.kauth.adapter.web.api

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServer
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
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the v1.21.0 resource servers CRUD REST API:
 *   - GET    /resource-servers
 *   - POST   /resource-servers
 *   - GET    /resource-servers/{id}
 *   - PUT    /resource-servers/{id}
 *   - DELETE /resource-servers/{id}
 *   - GET    /applications/{appId}/authorized-resource-servers
 *   - PUT    /applications/{appId}/authorized-resource-servers
 */
class ApiResourceServerRoutesTest {
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
    private val resourceServerRepo = FakeResourceServerRepository()

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = "https://acme.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(),
        )

    private val otherTenant =
        Tenant(
            id = TenantId(2),
            slug = "other",
            displayName = "Other Corp",
            issuerUrl = "https://other.kotauth.dev",
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
            collisionCheck =
                com.kauth.domain.service
                    .IdentifierCollisionCheck(userRepo),
            usernameGenerator =
                com.kauth.domain.service
                    .UsernameGenerator(userRepo),
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

    private val resourceServerService = ResourceServerService(resourceServerRepo)

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private var rawApiKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
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
        resourceServerRepo.clear()

        tenantRepo.add(tenant)
        tenantRepo.add(otherTenant)

        rawApiKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Test Key",
                    scopes = listOf(ApiScope.RESOURCE_SERVERS_READ, ApiScope.RESOURCE_SERVERS_WRITE),
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

    private fun createBody(
        identifier: String = "https://api.acme.dev",
        name: String = "Acme API",
        description: String = "Internal API",
        scopes: String = """["read:orders", "write:orders"]""",
    ) = """{"identifier":"$identifier","name":"$name","description":"$description","scopes":$scopes}"""

    private fun updateBody(
        name: String = "Acme API v2",
        description: String = "Updated description",
        scopes: String = """["read:orders"]""",
    ) = """{"name":"$name","description":"$description","scopes":$scopes}"""

    // -------------------------------------------------------------------------
    // GET /resource-servers
    // -------------------------------------------------------------------------

    @Test
    fun `GET resource-servers returns empty envelope`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/resource-servers") { bearerAuth(rawApiKey) }

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
    fun `GET resource-servers returns configured servers`() =
        testApplication {
            application { installTestApp() }
            resourceServerRepo.seed(
                ResourceServer(
                    tenantId = TenantId(1),
                    identifier = "https://api.acme.dev",
                    name = "Acme API",
                    description = "Internal API",
                    scopes = listOf("read:orders"),
                ),
            )

            val response = client.get("/t/acme/api/v1/resource-servers") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(1, data.size)
            val dto = data[0].jsonObject
            assertEquals("https://api.acme.dev", dto["identifier"]!!.jsonPrimitive.content)
            assertEquals("Acme API", dto["name"]!!.jsonPrimitive.content)
            assertTrue(dto["enabled"]!!.jsonPrimitive.content.toBoolean())
        }

    // -------------------------------------------------------------------------
    // POST /resource-servers
    // -------------------------------------------------------------------------

    @Test
    fun `POST resource-servers creates and returns 201`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/resource-servers") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody())
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("https://api.acme.dev", body["identifier"]!!.jsonPrimitive.content)
            assertEquals("Acme API", body["name"]!!.jsonPrimitive.content)
            assertEquals(1, resourceServerRepo.findByTenantId(TenantId(1)).size)
        }

    @Test
    fun `POST resource-servers returns 422 for invalid identifier with spaces and uppercase`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/resource-servers") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(identifier = "Invalid Identifier With Spaces"))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST resource-servers returns 422 for blank name`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/resource-servers") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(name = ""))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST resource-servers returns 409 for duplicate identifier`() =
        testApplication {
            application { installTestApp() }
            resourceServerRepo.seed(
                ResourceServer(
                    tenantId = TenantId(1),
                    identifier = "https://api.acme.dev",
                    name = "Existing API",
                ),
            )

            val response =
                client.post("/t/acme/api/v1/resource-servers") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody())
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    // -------------------------------------------------------------------------
    // GET /resource-servers/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `GET resource-servers id returns 404 for unknown id`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/resource-servers/999999") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET resource-servers id returns 404 when server belongs to another tenant`() =
        testApplication {
            application { installTestApp() }
            val other =
                resourceServerRepo.seed(
                    ResourceServer(
                        tenantId = TenantId(2),
                        identifier = "https://api.other.dev",
                        name = "Other API",
                    ),
                )

            val response = client.get("/t/acme/api/v1/resource-servers/${other.id!!.value}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // -------------------------------------------------------------------------
    // PUT /resource-servers/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `PUT resource-servers id updates name description and scopes`() =
        testApplication {
            application { installTestApp() }
            val existing =
                resourceServerRepo.seed(
                    ResourceServer(
                        tenantId = TenantId(1),
                        identifier = "https://api.acme.dev",
                        name = "Acme API",
                        description = "Old description",
                        scopes = listOf("read:orders", "write:orders"),
                    ),
                )

            val response =
                client.put("/t/acme/api/v1/resource-servers/${existing.id!!.value}") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(updateBody())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Acme API v2", body["name"]!!.jsonPrimitive.content)
            assertEquals("Updated description", body["description"]!!.jsonPrimitive.content)
            assertEquals(listOf("read:orders"), body["scopes"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `PUT resource-servers id returns 422 for blank name`() =
        testApplication {
            application { installTestApp() }
            val existing =
                resourceServerRepo.seed(
                    ResourceServer(
                        tenantId = TenantId(1),
                        identifier = "https://api.acme.dev",
                        name = "Acme API",
                    ),
                )

            val response =
                client.put("/t/acme/api/v1/resource-servers/${existing.id!!.value}") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(updateBody(name = ""))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    // -------------------------------------------------------------------------
    // DELETE /resource-servers/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `DELETE resource-servers id returns 204`() =
        testApplication {
            application { installTestApp() }
            val existing =
                resourceServerRepo.seed(
                    ResourceServer(
                        tenantId = TenantId(1),
                        identifier = "https://api.acme.dev",
                        name = "Acme API",
                    ),
                )

            val response =
                client.delete("/t/acme/api/v1/resource-servers/${existing.id!!.value}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(resourceServerRepo.findByTenantId(TenantId(1)).isEmpty())
        }

    @Test
    fun `DELETE resource-servers id returns 404 for unknown id`() =
        testApplication {
            application { installTestApp() }

            val response = client.delete("/t/acme/api/v1/resource-servers/999999") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // -------------------------------------------------------------------------
    // GET/PUT /applications/{appId}/authorized-resource-servers
    // -------------------------------------------------------------------------

    @Test
    fun `GET authorized-resource-servers returns list`() =
        testApplication {
            application { installTestApp() }
            val app =
                appRepo.add(
                    Application(
                        id = ApplicationId(0),
                        tenantId = TenantId(1),
                        clientId = "acme-app",
                        name = "Acme App",
                        description = null,
                        accessType = AccessType.PUBLIC,
                        enabled = true,
                    ),
                )
            val rs =
                resourceServerRepo.seed(
                    ResourceServer(
                        tenantId = TenantId(1),
                        identifier = "https://api.acme.dev",
                        name = "Acme API",
                    ),
                )
            resourceServerRepo.registerClient(app.id, TenantId(1))
            resourceServerRepo.setAuthorizedResources(app.id, listOf(rs.id!!))

            val response =
                client.get("/t/acme/api/v1/applications/${app.id.value}/authorized-resource-servers") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(1, data.size)
            assertEquals("https://api.acme.dev", data[0].jsonObject["identifier"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PUT authorized-resource-servers replaces list`() =
        testApplication {
            application { installTestApp() }
            val app =
                appRepo.add(
                    Application(
                        id = ApplicationId(0),
                        tenantId = TenantId(1),
                        clientId = "acme-app",
                        name = "Acme App",
                        description = null,
                        accessType = AccessType.PUBLIC,
                        enabled = true,
                    ),
                )
            val rs1 =
                resourceServerRepo.seed(
                    ResourceServer(tenantId = TenantId(1), identifier = "https://api-1.acme.dev", name = "API 1"),
                )
            val rs2 =
                resourceServerRepo.seed(
                    ResourceServer(tenantId = TenantId(1), identifier = "https://api-2.acme.dev", name = "API 2"),
                )
            resourceServerRepo.registerClient(app.id, TenantId(1))
            resourceServerRepo.setAuthorizedResources(app.id, listOf(rs1.id!!))

            val response =
                client.put("/t/acme/api/v1/applications/${app.id.value}/authorized-resource-servers") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"resourceServerIds":[${rs2.id!!.value}]}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val authorized = resourceServerRepo.listAuthorizedFor(app.id)
            assertEquals(listOf(rs2.id!!.value), authorized.map { it.id!!.value })
        }

    @Test
    fun `PUT authorized-resource-servers returns 404 for unknown app`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/applications/999999/authorized-resource-servers") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"resourceServerIds":[]}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // -------------------------------------------------------------------------
    // Scope enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `Scope enforcement key without resource_servers_write gets 403 on POST`() =
        testApplication {
            application { installTestApp() }
            val readOnlyKey = apiKeyWithScopes(listOf(ApiScope.RESOURCE_SERVERS_READ))

            val response =
                client.post("/t/acme/api/v1/resource-servers") {
                    bearerAuth(readOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody())
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
                webhookService = webhookService,
                resourceServerService = resourceServerService,
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
