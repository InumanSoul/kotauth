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
import com.kauth.domain.service.WorkspacePatchService
import com.kauth.domain.service.WorkspaceSettingsService
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
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.fakes.FakeWebhookSender
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
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

    private val workspacePatchService =
        WorkspacePatchService(
            tenantRepository = tenantRepo,
            workspaceSettingsService =
                WorkspaceSettingsService(tenantRepo, auditLogPort, themeRepository = FakeThemeRepository()),
            accountService = adminService,
            availableLocales = { setOf("en", "es") },
            transactionRunner = com.kauth.fakes.FakeTransactionRunner(tenantRepo),
        )

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
        tenantRepo.add(Tenant(id = TenantId(2), slug = "other", displayName = "Other", issuerUrl = null))

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
    // PATCH /workspace
    // -------------------------------------------------------------------------

    private fun writeKey(): String = apiKeyMissingScope(listOf(ApiScope.WORKSPACE_READ, ApiScope.WORKSPACE_WRITE))

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.patchWorkspace(
        body: String,
        key: String = writeKey(),
        slug: String = "acme",
    ) = client.patch("/t/$slug/api/v1/workspace") {
        bearerAuth(key)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `PATCH workspace changes the given settings and GET shows them`() =
        testApplication {
            application { installTestApp() }

            val response =
                patchWorkspace(
                    """
                    {"registrationEnabled": false, "mfaPolicy": "optional", "tokenExpirySeconds": 600,
                     "signInMethods": {"password": false},
                     "passwordPolicy": {"minLength": 16},
                     "lockoutMaxAttempts": 3, "loginIdentifierMode": "email"}
                    """.trimIndent(),
                )
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            val body =
                jsonCodec
                    .parseToJsonElement(
                        client.get("/t/acme/api/v1/workspace") { bearerAuth(rawApiKey) }.bodyAsText(),
                    ).jsonObject
            assertFalse(body["registrationEnabled"]!!.jsonPrimitive.boolean)
            assertEquals("optional", body["mfaPolicy"]!!.jsonPrimitive.content)
            assertEquals(600, body["tokenExpirySeconds"]!!.jsonPrimitive.content.toInt())
            assertFalse(body["signInMethods"]!!.jsonObject["password"]!!.jsonPrimitive.boolean)
            assertTrue(body["signInMethods"]!!.jsonObject["emailOtp"]!!.jsonPrimitive.boolean)
            assertEquals(
                16,
                body["passwordPolicy"]!!
                    .jsonObject["minLength"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(3, body["lockoutMaxAttempts"]!!.jsonPrimitive.content.toInt())
            assertEquals("EMAIL", body["loginIdentifierMode"]!!.jsonPrimitive.content)
            // Untouched fields keep their values.
            assertEquals("Acme Corp", body["displayName"]!!.jsonPrimitive.content)
            assertTrue(body["passwordPolicy"]!!.jsonObject["requireSpecial"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `PATCH workspace sets defaultLocale`() =
        testApplication {
            application { installTestApp() }

            val response = patchWorkspace("""{"defaultLocale": "es"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("es", body["defaultLocale"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PATCH workspace writes SMTP and never returns the password`() =
        testApplication {
            application { installTestApp() }

            val response =
                patchWorkspace(
                    """{"smtp": {"host": "mail.acme.example", "port": 2525, "password": "new-smtp-secret"}}""",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(response.bodyAsText().contains("new-smtp-secret"))
            assertFalse(response.bodyAsText().contains("smtp", ignoreCase = true))
            val stored = tenantRepo.findBySlug("acme")!!
            assertEquals("mail.acme.example", stored.smtpHost)
            assertEquals(2525, stored.smtpPort)
            assertEquals("new-smtp-secret", stored.smtpPassword)
            assertEquals("noreply@acme.example", stored.smtpFromAddress)
        }

    @Test
    fun `PATCH workspace switches SMTP off and reports email delivery as not ready`() =
        testApplication {
            application { installTestApp() }

            val response = patchWorkspace("""{"smtp": {"enabled": false}}""")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertFalse(body["emailDeliveryReady"]!!.jsonPrimitive.boolean)
            assertEquals("super-secret-smtp-password", tenantRepo.findBySlug("acme")!!.smtpPassword)
        }

    @Test
    fun `PATCH workspace 403 when key lacks workspace_write scope`() =
        testApplication {
            application { installTestApp() }

            val response = patchWorkspace("""{"registrationEnabled": false}""", key = rawApiKey)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(tenantRepo.findBySlug("acme")!!.registrationEnabled)
        }

    @Test
    fun `PATCH workspace with a key of another workspace changes nothing`() =
        testApplication {
            application { installTestApp() }

            val response = patchWorkspace("""{"registrationEnabled": false}""", slug = "other")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(tenantRepo.findBySlug("other")!!.registrationEnabled)
        }

    @Test
    fun `PATCH workspace 400 on an unknown field`() =
        testApplication {
            application { installTestApp() }

            val response = patchWorkspace("""{"registrationEnabld": false}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `PATCH workspace 422 on an invalid value, leaving the workspace unchanged`() =
        testApplication {
            application { installTestApp() }

            val response =
                patchWorkspace("""{"registrationEnabled": false, "smtp": {"port": 2525}, "mfaPolicy": "sometimes"}""")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val stored = tenantRepo.findBySlug("acme")!!
            assertTrue(stored.registrationEnabled)
            assertEquals(587, stored.smtpPort)
        }

    @Test
    fun `PATCH workspace 422 on an unknown loginIdentifierMode or locale`() =
        testApplication {
            application { installTestApp() }
            val key = writeKey()

            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                patchWorkspace("""{"loginIdentifierMode": "phone"}""", key).status,
            )
            assertEquals(HttpStatusCode.UnprocessableEntity, patchWorkspace("""{"defaultLocale": "xx"}""", key).status)
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
                webhookService =
                    WebhookService(
                        FakeWebhookEndpointRepository(),
                        FakeWebhookDeliveryRepository(),
                        FakeWebhookSender(),
                    ),
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
                workspacePatchService = workspacePatchService,
            )
        }
    }
}
