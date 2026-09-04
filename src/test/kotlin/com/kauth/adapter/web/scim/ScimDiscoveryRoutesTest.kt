package com.kauth.adapter.web.scim

import com.kauth.adapter.web.api.AlwaysAllowLimiter
import com.kauth.adapter.web.api.apiRoutes
import com.kauth.adapter.web.api.stubEmailOtpService
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
import com.kauth.fakes.FakeTransactionRunner
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the SCIM discovery surface mounted at `/t/{tenantSlug}/scim/v2`:
 *   - GET /ServiceProviderConfig
 *   - GET /ResourceTypes
 *   - GET /Schemas
 */
class ScimDiscoveryRoutesTest {
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

    private val acme =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = "https://acme.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(),
        )

    private val globex =
        Tenant(
            id = TenantId(2),
            slug = "globex",
            displayName = "Globex Corp",
            issuerUrl = "https://globex.kotauth.dev",
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

    private val claimMapperService = CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private val webhookService =
        WebhookService(
            endpointRepository = FakeWebhookEndpointRepository(),
            deliveryRepository = FakeWebhookDeliveryRepository(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private var scimKey: String = ""
    private var noScimKey: String = ""

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

        tenantRepo.add(acme)
        tenantRepo.add(globex)

        scimKey =
            (
                apiKeyService.create(
                    tenantId = acme.id,
                    name = "Provisioning Key",
                    scopes = listOf(ApiScope.SCIM),
                ) as ApiKeyResult.Success
            ).value.rawKey

        noScimKey =
            (
                apiKeyService.create(
                    tenantId = acme.id,
                    name = "Other Key",
                    scopes = listOf(ApiScope.USERS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    // -------------------------------------------------------------------------
    // GET /ServiceProviderConfig
    // -------------------------------------------------------------------------

    @Test
    fun `GET ServiceProviderConfig advertises the implemented capabilities honestly`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/ServiceProviderConfig") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["patch"]!!.jsonObject["supported"]!!.jsonPrimitive.boolean)
            assertTrue(body["filter"]!!.jsonObject["supported"]!!.jsonPrimitive.boolean)
            assertFalse(body["bulk"]!!.jsonObject["supported"]!!.jsonPrimitive.boolean)
            assertFalse(body["sort"]!!.jsonObject["supported"]!!.jsonPrimitive.boolean)
            assertFalse(body["etag"]!!.jsonObject["supported"]!!.jsonPrimitive.boolean)
            assertFalse(body["changePassword"]!!.jsonObject["supported"]!!.jsonPrimitive.boolean)
            assertTrue(
                body["filter"]!!
                    .jsonObject["maxResults"]!!
                    .jsonPrimitive.content
                    .toInt() > 0,
            )
        }

    // -------------------------------------------------------------------------
    // GET /ResourceTypes
    // -------------------------------------------------------------------------

    @Test
    fun `GET ResourceTypes lists User and Group with their endpoints and schema URNs`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/ResourceTypes") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val resources = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject["Resources"]!!.jsonArray
            val byId = resources.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }

            assertEquals(
                "/Users",
                byId
                    .getValue("User")
                    .jsonObject["endpoint"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "urn:ietf:params:scim:schemas:core:2.0:User",
                byId
                    .getValue("User")
                    .jsonObject["schema"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "/Groups",
                byId
                    .getValue("Group")
                    .jsonObject["endpoint"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "urn:ietf:params:scim:schemas:core:2.0:Group",
                byId
                    .getValue("Group")
                    .jsonObject["schema"]!!
                    .jsonPrimitive.content,
            )
        }

    // -------------------------------------------------------------------------
    // GET /Schemas
    // -------------------------------------------------------------------------

    @Test
    fun `GET Schemas returns the core User and Group schemas`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/Schemas") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val resources = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject["Resources"]!!.jsonArray
            val ids = resources.map { it.jsonObject["id"]!!.jsonPrimitive.content }

            assertTrue("urn:ietf:params:scim:schemas:core:2.0:User" in ids)
            assertTrue("urn:ietf:params:scim:schemas:core:2.0:Group" in ids)
        }

    @Test
    fun `the User schema advertises userName as case-sensitive and immutable`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/Schemas") { bearerAuth(scimKey) }
            val userSchema =
                jsonCodec
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["Resources"]!!
                    .jsonArray
                    .first {
                        it.jsonObject["id"]!!.jsonPrimitive.content == "urn:ietf:params:scim:schemas:core:2.0:User"
                    }
            val userName =
                userSchema.jsonObject["attributes"]!!
                    .jsonArray
                    .first { it.jsonObject["name"]!!.jsonPrimitive.content == "userName" }
                    .jsonObject

            // Matching is case-sensitive end to end (ScimFilter, PostgresUserRepository), and a
            // PUT/PATCH rename is rejected — the schema must not claim otherwise.
            assertEquals(true, userName["caseExact"]?.jsonPrimitive?.boolean)
            assertEquals("immutable", userName["mutability"]?.jsonPrimitive?.content)
        }

    @Test
    fun `the User schema advertises externalId as case-sensitive and server-unique, matching the DB index`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/Schemas") { bearerAuth(scimKey) }
            val userSchema =
                jsonCodec
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["Resources"]!!
                    .jsonArray
                    .first {
                        it.jsonObject["id"]!!.jsonPrimitive.content == "urn:ietf:params:scim:schemas:core:2.0:User"
                    }
            val externalId =
                userSchema.jsonObject["attributes"]!!
                    .jsonArray
                    .first { it.jsonObject["name"]!!.jsonPrimitive.content == "externalId" }
                    .jsonObject

            assertEquals(true, externalId["caseExact"]?.jsonPrimitive?.boolean)
            assertEquals("server", externalId["uniqueness"]?.jsonPrimitive?.content)
        }

    // -------------------------------------------------------------------------
    // Scope enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `a key without the scim scope gets 403 in the SCIM error envelope, not the REST one`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/ServiceProviderConfig") { bearerAuth(noScimKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val schemas = body["schemas"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("urn:ietf:params:scim:api:messages:2.0:Error"), schemas)
            // The REST problem+json envelope has "title"/"type" fields instead — their absence
            // here confirms this went through the SCIM envelope, not the REST one.
            assertTrue(body["title"] == null)
        }

    // -------------------------------------------------------------------------
    // Tenant resolution
    // -------------------------------------------------------------------------

    @Test
    fun `an unknown tenant slug gets 404`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/nonexistent/scim/v2/ServiceProviderConfig") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `a key scoped to one workspace cannot read another workspace's SCIM surface`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/globex/scim/v2/ServiceProviderConfig") { bearerAuth(scimKey) }

            // ApiKeyService.validate rejects on tenant mismatch before any scope check runs, so
            // this fails at authentication (401, REST envelope) rather than reaching
            // requireScimScope's 403 (SCIM envelope) — pin the REST shape, not just non-200.
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Invalid API key", body["title"]?.jsonPrimitive?.content)
            assertTrue(body["schemas"] == null)
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
                transactionRunner = FakeTransactionRunner.passThrough(),
            )
        }
    }
}
