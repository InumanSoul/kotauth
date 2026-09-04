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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the v1.21.0 read-only `GET /workspace` endpoint.
 */
class ApiWorkspaceRoutesTest {
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
            displayName = "Acme Corp",
            issuerUrl = "https://acme.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            passkeysEnabled = true,
            securityConfig =
                SecurityConfig(
                    passwordMinLength = 12,
                    passwordRequireSpecial = true,
                    passwordRequireUppercase = true,
                    passwordRequireNumber = true,
                    passwordHistoryCount = 5,
                    passwordMaxAgeDays = 90,
                    passwordBlacklistEnabled = true,
                    mfaPolicy = "required",
                    lockoutMaxAttempts = 5,
                    lockoutDurationMinutes = 30,
                    corsAllowCredentials = true,
                    hibpCheckEnabled = true,
                    magicLinkEnabled = true,
                    magicLinkTokenTtlMinutes = 20,
                    passwordLoginEnabled = true,
                    emailOtpSignupEnabled = true,
                    emailOtpLockoutThreshold = 3,
                    emailOtpLoginEnabled = true,
                ),
            smtpHost = "smtp.acme.example",
            smtpUsername = "smtp-user",
            smtpPassword = "super-secret-smtp-password",
            smtpFromAddress = "noreply@acme.example",
            smtpEnabled = true,
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
                    scopes = listOf(ApiScope.WORKSPACE_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun apiKeyMissingScope(scopes: List<String>): String =
        (
            apiKeyService.create(
                tenantId = TenantId(1),
                name = "Limited Key",
                scopes = scopes,
            ) as ApiKeyResult.Success
        ).value.rawKey

    @Test
    fun `GET workspace returns tenant metadata`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/workspace") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["id"]!!.jsonPrimitive.content.toInt())
            assertEquals("acme", body["slug"]!!.jsonPrimitive.content)
            assertEquals("Acme Corp", body["displayName"]!!.jsonPrimitive.content)
            assertEquals("https://acme.kotauth.dev", body["issuerUrl"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET workspace signInMethods reflects SecurityConfig flags`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/workspace") { bearerAuth(rawApiKey) }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val signInMethods = body["signInMethods"]!!.jsonObject
            assertTrue(signInMethods["password"]!!.jsonPrimitive.boolean)
            assertTrue(signInMethods["passkey"]!!.jsonPrimitive.boolean)
            assertTrue(signInMethods["magicLink"]!!.jsonPrimitive.boolean)
            assertTrue(signInMethods["emailOtp"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `GET workspace passwordPolicy reflects SecurityConfig`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/workspace") { bearerAuth(rawApiKey) }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val passwordPolicy = body["passwordPolicy"]!!.jsonObject
            assertEquals(12, passwordPolicy["minLength"]!!.jsonPrimitive.content.toInt())
            assertTrue(passwordPolicy["requireSpecial"]!!.jsonPrimitive.boolean)
            assertTrue(passwordPolicy["requireUppercase"]!!.jsonPrimitive.boolean)
            assertTrue(passwordPolicy["requireNumber"]!!.jsonPrimitive.boolean)
            assertEquals(5, passwordPolicy["historyCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(90, passwordPolicy["maxAgeDays"]!!.jsonPrimitive.content.toInt())
            assertTrue(passwordPolicy["blacklistEnabled"]!!.jsonPrimitive.boolean)
            assertTrue(passwordPolicy["hibpCheckEnabled"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `GET workspace does NOT expose smtpHost or smtpPassword under any key`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/workspace") { bearerAuth(rawApiKey) }
            val rawBody = response.bodyAsText()

            assertFalse(rawBody.contains("smtp", ignoreCase = true))
        }

    @Test
    fun `GET workspace mfaPolicy round-trips string`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/workspace") { bearerAuth(rawApiKey) }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("required", body["mfaPolicy"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET workspace 403 when key lacks workspace_read scope`() =
        testApplication {
            application { installTestApp() }
            val limitedKey = apiKeyMissingScope(listOf(ApiScope.USERS_READ))

            val response = client.get("/t/acme/api/v1/workspace") { bearerAuth(limitedKey) }

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
