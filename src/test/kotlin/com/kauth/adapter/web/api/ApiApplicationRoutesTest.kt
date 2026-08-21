package com.kauth.adapter.web.api

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Application
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.ApplicationManagementService
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the v1.21.0 application CRUD endpoints:
 *   - POST   /applications
 *   - DELETE /applications/{id}
 *   - POST   /applications/{id}/regenerate-secret
 */
class ApiApplicationRoutesTest {
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
        ApplicationManagementService(
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
        appRepo.clear()
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
                    scopes = listOf(ApiScope.APPLICATIONS_WRITE, ApiScope.APPLICATIONS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun seedApp(
        clientId: String = "existing-app",
        accessType: String = "public",
    ): Application =
        appRepo.create(
            tenantId = TenantId(1),
            clientId = clientId,
            name = "Existing App",
            description = null,
            accessType = accessType,
            redirectUris = listOf("https://x/cb"),
            grantTypes = GrantType.defaultsFor(AccessType.fromValue(accessType)),
            clientSecretHash = null,
            audience = null,
        )

    private fun apiKeyMissingScope(scopes: List<String>): String =
        (
            apiKeyService.create(
                tenantId = TenantId(1),
                name = "Limited Key",
                scopes = scopes,
            ) as ApiKeyResult.Success
        ).value.rawKey

    private fun createAppBody(
        clientId: String = "new-app",
        accessType: String = "public",
        redirectUris: String = """["https://example.com/callback"]""",
    ) = """{"clientId":"$clientId","name":"New App","accessType":"$accessType","redirectUris":$redirectUris}"""

    // =========================================================================
    // POST /applications
    // =========================================================================

    @Test
    fun `POST applications creates a public app and returns clientSecret null`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createAppBody(accessType = "public"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("new-app", body["application"]!!.jsonObject["clientId"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, body["clientSecret"])
        }

    @Test
    fun `POST applications creates a confidential app and returns non-null clientSecret`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createAppBody(accessType = "confidential"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val secret = body["clientSecret"]!!.jsonPrimitive.content
            assertTrue(secret.isNotBlank())
            val app = appRepo.findByClientId(TenantId(1), "new-app")!!
            assertEquals("hashed:$secret", appRepo.findClientSecretHash(app.id))
        }

    @Test
    fun `POST applications omitting grantTypes defaults to authorization code and refresh token`() =
        testApplication {
            application { installTestApp() }

            client.post("/t/acme/api/v1/applications") {
                bearerAuth(rawApiKey)
                contentType(ContentType.Application.Json)
                setBody(createAppBody())
            }

            val app = appRepo.findByClientId(TenantId(1), "new-app")!!
            assertEquals(setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN), app.grantTypes)
        }

    @Test
    fun `POST applications with explicit grantTypes stores exactly the requested set`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"clientId":"m2m-app","name":"M2M App","accessType":"confidential",""" +
                            """"redirectUris":[],"grantTypes":["client_credentials"]}""",
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val app = appRepo.findByClientId(TenantId(1), "m2m-app")!!
            assertEquals(setOf(GrantType.CLIENT_CREDENTIALS), app.grantTypes)
        }

    @Test
    fun `POST applications returns 422 when clientId has uppercase`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createAppBody(clientId = "New-App"))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST applications returns 422 when redirectUris is empty`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createAppBody(redirectUris = "[]"))
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST applications returns 409 when clientId already exists`() =
        testApplication {
            application { installTestApp() }
            seedApp(clientId = "new-app")

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createAppBody(clientId = "new-app"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `POST applications returns 409 when clientId matches a soft-deleted app`() =
        testApplication {
            application { installTestApp() }
            val app = seedApp(clientId = "new-app")
            appRepo.softDelete(app.id)
            // Confirm the reuse-blocking policy premise: soft-deleted apps are invisible to reads...
            assertNull(appRepo.findByClientId(TenantId(1), "new-app"))

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody(createAppBody(clientId = "new-app"))
                }

            // ...but existsByClientId still sees them, so client_id remains globally unique.
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `scope enforcement key without APPLICATIONS_WRITE gets 403 on POST applications`() =
        testApplication {
            application { installTestApp() }
            val limitedKey = apiKeyMissingScope(listOf(ApiScope.APPLICATIONS_READ))

            val response =
                client.post("/t/acme/api/v1/applications") {
                    bearerAuth(limitedKey)
                    contentType(ContentType.Application.Json)
                    setBody(createAppBody())
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // =========================================================================
    // DELETE /applications/{id}
    // =========================================================================

    @Test
    fun `DELETE applications returns 204 and marks app as deleted`() =
        testApplication {
            application { installTestApp() }
            val app = seedApp()

            val response =
                client.delete("/t/acme/api/v1/applications/${app.id.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertNull(appRepo.findById(app.id))
            assertEquals(1, auditLogPort.countOf(com.kauth.domain.model.AuditEventType.ADMIN_CLIENT_DELETED))
        }

    @Test
    fun `DELETE applications subsequent GET returns 404`() =
        testApplication {
            application { installTestApp() }
            val app = seedApp()

            client.delete("/t/acme/api/v1/applications/${app.id.value}") {
                bearerAuth(rawApiKey)
            }
            val getResponse =
                client.get("/t/acme/api/v1/applications/${app.id.value}") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, getResponse.status)
        }

    @Test
    fun `DELETE applications returns 404 for unknown id`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.delete("/t/acme/api/v1/applications/99999") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // =========================================================================
    // POST /applications/{id}/regenerate-secret
    // =========================================================================

    @Test
    fun `POST regenerate-secret returns new secret and hashes it into storage`() =
        testApplication {
            application { installTestApp() }
            val app = seedApp(accessType = "confidential")

            val response =
                client.post("/t/acme/api/v1/applications/${app.id.value}/regenerate-secret") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val secret = body["clientSecret"]!!.jsonPrimitive.content
            assertTrue(secret.isNotBlank())
            assertEquals("hashed:$secret", appRepo.findClientSecretHash(app.id))
        }

    @Test
    fun `POST regenerate-secret returns 404 for unknown id`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/applications/99999/regenerate-secret") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
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
            )
        }
    }
}
