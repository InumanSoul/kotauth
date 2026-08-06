package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.TokenPurpose
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
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
import io.ktor.client.request.bearerAuth
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the three programmatic user-lifecycle endpoints
 * added in v1.6.1:
 *   - POST /users/invite
 *   - POST /users/{id}/send-reset-email
 *   - POST /users/{id}/temporary-password
 *
 * Validates the SaaS integrator happy path (Oriana-style): provision a user
 * without a password, let KotAuth handle invite email + first-login setup.
 */
class ApiUserLifecycleRoutesTest {
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

    /** SMTP-ready tenant — invite and reset endpoints validate SMTP is configured. */
    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            smtpEnabled = true,
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            smtpUsername = "noreply@acme.com",
            smtpPassword = "secret",
            smtpFromAddress = "noreply@acme.com",
            smtpFromName = "Acme",
        )

    private val existingUser =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = hasher.hash("correct-pass"),
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

    private val claimMapperService =
        CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private var rawApiKey: String = ""
    private var readOnlyKey: String = ""

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
        userRepo.add(existingUser)

        rawApiKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Test Key",
                    scopes = listOf(ApiScope.USERS_WRITE, ApiScope.USERS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
        readOnlyKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Read Only",
                    scopes = listOf(ApiScope.USERS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    // =========================================================================
    // POST /users/invite
    // =========================================================================

    @Test
    fun `POST invite creates user with sentinel password and SET_PASSWORD action`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/invite") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"bob","email":"bob@acme.com","fullName":"Bob"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val bob = userRepo.findByUsername(TenantId(1), "bob")!!
            assertEquals(User.SENTINEL_PASSWORD_HASH, bob.passwordHash)
            assertTrue(RequiredAction.SET_PASSWORD in bob.requiredActions)
        }

    @Test
    fun `POST invite generates an INVITE token`() =
        testApplication {
            application { installTestApp() }
            client.post("/t/acme/api/v1/users/invite") {
                bearerAuth(rawApiKey)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"carol","email":"carol@acme.com","fullName":"Carol"}""")
            }

            val tokens = prTokenRepo.all().filter { it.purpose == TokenPurpose.INVITE }
            assertEquals(1, tokens.size)
        }

    @Test
    fun `POST invite sends an invite email`() =
        testApplication {
            application { installTestApp() }
            client.post("/t/acme/api/v1/users/invite") {
                bearerAuth(rawApiKey)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"dave","email":"dave@acme.com","fullName":"Dave"}""")
            }
            assertEquals(1, emailPort.sent.size)
            assertEquals("dave@acme.com", emailPort.sent.single().to)
        }

    @Test
    fun `POST invite returns 403 without USERS_WRITE scope`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/invite") {
                    bearerAuth(readOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"x","email":"x@acme.com","fullName":"X"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `POST invite returns 422 for duplicate username`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/invite") {
                    bearerAuth(rawApiKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"alice","email":"other@acme.com","fullName":"Other"}""")
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    // =========================================================================
    // POST /users/{id}/send-reset-email
    // =========================================================================

    @Test
    fun `POST send-reset-email generates a reset token and returns 204`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/10/send-reset-email") {
                    bearerAuth(rawApiKey)
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val tokens = prTokenRepo.all().filter { it.purpose == TokenPurpose.PASSWORD_RESET }
            assertEquals(1, tokens.size)
        }

    @Test
    fun `POST send-reset-email returns 404 for unknown user`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/999/send-reset-email") {
                    bearerAuth(rawApiKey)
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `POST send-reset-email returns 403 without USERS_WRITE scope`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/10/send-reset-email") {
                    bearerAuth(readOnlyKey)
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // =========================================================================
    // POST /users/{id}/temporary-password
    // =========================================================================

    @Test
    fun `POST temporary-password returns a change-password URL and sets CHANGE_PASSWORD`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/10/temporary-password") {
                    bearerAuth(rawApiKey)
                }
            assertEquals(HttpStatusCode.Created, response.status)

            val body = response.bodyAsText()
            assertTrue(body.contains("changePasswordUrl"))
            assertTrue(body.contains("/t/acme/change-password?token="))
            assertTrue(body.contains("expiresAt"))

            val fresh = userRepo.findById(UserId(10), TenantId(1))!!
            assertTrue(RequiredAction.CHANGE_PASSWORD in fresh.requiredActions)
        }

    @Test
    fun `POST temporary-password generates a TEMP_PASSWORD token`() =
        testApplication {
            application { installTestApp() }
            client.post("/t/acme/api/v1/users/10/temporary-password") {
                bearerAuth(rawApiKey)
            }
            val tokens = prTokenRepo.all().filter { it.purpose == TokenPurpose.TEMP_PASSWORD }
            assertEquals(1, tokens.size)
        }

    @Test
    fun `POST temporary-password returns 404 for unknown user`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/999/temporary-password") {
                    bearerAuth(rawApiKey)
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `POST temporary-password returns 403 without USERS_WRITE scope`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/t/acme/api/v1/users/10/temporary-password") {
                    bearerAuth(readOnlyKey)
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
