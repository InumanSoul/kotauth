package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.UserAttributeService
import com.kauth.domain.service.WebAuthnService
import com.kauth.domain.service.WebhookService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.infrastructure.ApiKeyPrincipal
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the user-attributes REST API.
 * Uses a stripped-down wiring with only the deps these routes need.
 */
class ApiUserAttributeRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val userAttributeRepo = FakeUserAttributeRepository()
    private val hasher = FakePasswordHasher()

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

    private val apiKeyService =
        ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo)
    private val userAttributeService =
        UserAttributeService(userAttributeRepository = userAttributeRepo, userRepository = userRepo)

    private val tenant =
        Tenant(id = TenantId(1), slug = "acme", displayName = "Acme", issuerUrl = null, theme = TenantTheme.DEFAULT)
    private val user =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice",
            passwordHash = hasher.hash("pw"),
        )

    private var readKey: String = ""
    private var writeKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        apiKeyRepo.clear()
        userAttributeRepo.clear()

        tenantRepo.add(tenant)
        userRepo.add(user)

        readKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Read Key",
                    scopes = listOf(ApiScope.USER_ATTRIBUTES_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
        writeKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Write Key",
                    scopes = listOf(ApiScope.USER_ATTRIBUTES_READ, ApiScope.USER_ATTRIBUTES_WRITE),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    // =========================================================================
    // GET /users/{id}/attributes
    // =========================================================================

    @Test
    fun `GET attributes returns empty map for user with no attributes`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.get("/t/acme/api/v1/users/10/attributes") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"attributes\""))
        }

    @Test
    fun `GET attributes returns all stored attributes`() =
        testApplication {
            application { installTestApp() }
            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(1), "plan", "trial", Instant.now()))
            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(1), "trial_ends", "2026-05-21", Instant.now()))

            val response =
                client.get("/t/acme/api/v1/users/10/attributes") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"plan\":\"trial\""))
            assertTrue(body.contains("\"trial_ends\":\"2026-05-21\""))
        }

    @Test
    fun `GET attributes returns 404 for unknown user`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.get("/t/acme/api/v1/users/999/attributes") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET attributes returns 403 when API key lacks read scope`() =
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
                client.get("/t/acme/api/v1/users/10/attributes") { bearerAuth(noScopeKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET attributes returns 400 for non-integer user id`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.get("/t/acme/api/v1/users/abc/attributes") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // =========================================================================
    // PUT /users/{id}/attributes/{key}
    // =========================================================================

    @Test
    fun `PUT attribute creates new attribute`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.put("/t/acme/api/v1/users/10/attributes/plan") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"trial"}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("trial", userAttributeRepo.findAll(UserId(10), TenantId(1))["plan"])
        }

    @Test
    fun `PUT attribute overwrites existing value`() =
        testApplication {
            application { installTestApp() }
            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(1), "plan", "trial", Instant.now()))

            val response =
                client.put("/t/acme/api/v1/users/10/attributes/plan") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"pro"}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("pro", userAttributeRepo.findAll(UserId(10), TenantId(1))["plan"])
        }

    @Test
    fun `PUT attribute returns 403 when API key lacks write scope`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.put("/t/acme/api/v1/users/10/attributes/plan") {
                    bearerAuth(readKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"trial"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `PUT attribute returns 404 for unknown user`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.put("/t/acme/api/v1/users/999/attributes/plan") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"trial"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT attribute returns 422 when value too long`() =
        testApplication {
            application { installTestApp() }
            val tooLongValue = "v".repeat(UserAttribute.MAX_VALUE_LENGTH + 1)
            val response =
                client.put("/t/acme/api/v1/users/10/attributes/plan") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"$tooLongValue"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    // =========================================================================
    // DELETE /users/{id}/attributes/{key}
    // =========================================================================

    @Test
    fun `DELETE attribute removes it and returns 204`() =
        testApplication {
            application { installTestApp() }
            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(1), "plan", "trial", Instant.now()))

            val response =
                client.delete("/t/acme/api/v1/users/10/attributes/plan") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(userAttributeRepo.findAll(UserId(10), TenantId(1)).isEmpty())
        }

    @Test
    fun `DELETE non-existent attribute still returns 204`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.delete("/t/acme/api/v1/users/10/attributes/nonexistent") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE attribute returns 403 when API key lacks write scope`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.delete("/t/acme/api/v1/users/10/attributes/plan") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // =========================================================================
    // Cross-tenant isolation
    // =========================================================================

    @Test
    fun `API key from tenant A cannot read attributes in tenant B`() =
        testApplication {
            application { installTestApp() }
            // Add a second tenant and user.
            val otherTenant = tenant.copy(id = TenantId(2), slug = "other", displayName = "Other")
            tenantRepo.add(otherTenant)
            userRepo.add(user.copy(id = UserId(20), tenantId = TenantId(2), username = "bob"))

            // Attribute belongs to tenant B.
            userAttributeRepo.upsert(UserAttribute(UserId(20), TenantId(2), "plan", "secret", Instant.now()))

            // Request the tenant-B attribute using the tenant-A key.
            val response =
                client.get("/t/acme/api/v1/users/20/attributes") { bearerAuth(readKey) }

            // User 20 doesn't exist in tenant 1 (acme), so 404.
            assertEquals(HttpStatusCode.NotFound, response.status)
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
        // Required by apiRoutes signature — the attribute endpoint tests don't exercise it.
        val claimMapperService =
            com.kauth.infrastructure.CachingClaimMapperService(
                mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
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
