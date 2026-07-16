package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the v1.21.0 passkey admin REST API:
 *   - GET    /users/{userId}/passkeys
 *   - DELETE /passkeys/{credentialPk}
 */
class ApiPasskeyRoutesTest {
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
    private val credentialRepo = FakeWebAuthnCredentialRepository()
    private val relyingParty = FakeRelyingPartyAdapter()

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = "https://acme.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(),
            passkeysEnabled = true,
        )

    private val apiKeyService = ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo)

    private val webAuthnService =
        WebAuthnService(
            credentialRepository = credentialRepo,
            relyingParty = relyingParty,
            secretKey = "test-secret-key-32chars-long-xxxx",
            auditLog = auditLogPort,
            userRepository = userRepo,
            tenantRepository = tenantRepo,
        )

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
        credentialRepo.clear()

        tenantRepo.add(tenant)

        rawApiKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Test Key",
                    scopes = listOf(ApiScope.USERS_READ, ApiScope.USERS_WRITE),
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

    private fun seedCredential(
        userId: UserId,
        tenantId: TenantId = TenantId(1),
        credentialId: String = "cred-${UUID.randomUUID()}",
        name: String = "iPhone",
    ): WebAuthnCredential =
        credentialRepo.save(
            WebAuthnCredential(
                userId = userId,
                tenantId = tenantId,
                credentialId = credentialId,
                publicKeyCose = byteArrayOf(1, 2, 3, 4),
                signCounter = 7,
                aaguid = UUID.randomUUID(),
                transports = listOf("internal", "hybrid"),
                name = name,
                backupEligible = true,
                backupState = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
            ),
        )

    // -------------------------------------------------------------------------
    // GET /users/{userId}/passkeys
    // -------------------------------------------------------------------------

    @Test
    fun `GET users userId passkeys returns empty envelope when user has none`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/api/v1/users/10/passkeys") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["data"]!!.jsonArray.size)
            assertEquals(
                0,
                body["meta"]!!
                    .jsonObject["total"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `GET users userId passkeys returns seeded credentials`() =
        testApplication {
            application { installTestApp() }
            val userId = UserId(10)
            seedCredential(userId, name = "iPhone 15")
            seedCredential(userId, name = "YubiKey 5")

            val response = client.get("/t/acme/api/v1/users/10/passkeys") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(2, data.size)
            val names = data.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("iPhone 15", "YubiKey 5"), names)
        }

    @Test
    fun `GET users userId passkeys does not expose publicKeyCose or signCounter`() =
        testApplication {
            application { installTestApp() }
            seedCredential(UserId(10))

            val response = client.get("/t/acme/api/v1/users/10/passkeys") { bearerAuth(rawApiKey) }

            val raw = response.bodyAsText()
            assertFalse(raw.contains("publicKeyCose", ignoreCase = true))
            assertFalse(raw.contains("signCounter", ignoreCase = true))
        }

    // -------------------------------------------------------------------------
    // DELETE /passkeys/{credentialPk}
    // -------------------------------------------------------------------------

    @Test
    fun `DELETE passkeys id returns 204 and deletes the credential`() =
        testApplication {
            application { installTestApp() }
            val userId = UserId(10)
            val credential = seedCredential(userId)

            val response =
                client.delete("/t/acme/api/v1/passkeys/${credential.id}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(null, credentialRepo.findById(credential.id!!))
        }

    @Test
    fun `DELETE passkeys id returns 404 for unknown id`() =
        testApplication {
            application { installTestApp() }

            val response = client.delete("/t/acme/api/v1/passkeys/999999") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `DELETE passkeys id returns 404 when credential belongs to a different tenant`() =
        testApplication {
            application { installTestApp() }
            tenantRepo.add(
                Tenant(
                    id = TenantId(2),
                    slug = "globex",
                    displayName = "Globex",
                    issuerUrl = "https://globex.kotauth.dev",
                    theme = TenantTheme.DEFAULT,
                    securityConfig = SecurityConfig(),
                    passkeysEnabled = true,
                ),
            )
            val otherTenantCredential = seedCredential(UserId(99), tenantId = TenantId(2))

            val response =
                client.delete("/t/acme/api/v1/passkeys/${otherTenantCredential.id}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            // Not leaked: the credential still exists (untouched by the cross-tenant request).
            assertEquals(otherTenantCredential, credentialRepo.findById(otherTenantCredential.id!!))
        }

    @Test
    fun `DELETE passkeys id returns 409 when it is the user's last passkey and password login is disabled`() =
        testApplication {
            application { installTestApp() }
            tenantRepo.add(
                tenant.copy(securityConfig = SecurityConfig(passwordLoginEnabled = false)),
            )
            val userId = UserId(11)
            val onlyCredential = seedCredential(userId)

            val response =
                client.delete("/t/acme/api/v1/passkeys/${onlyCredential.id}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(onlyCredential, credentialRepo.findById(onlyCredential.id!!))
        }

    @Test
    fun `DELETE passkeys id emits PASSKEY_REVOKED audit event on success`() =
        testApplication {
            application { installTestApp() }
            val credential = seedCredential(UserId(10))

            val response =
                client.delete("/t/acme/api/v1/passkeys/${credential.id}") { bearerAuth(rawApiKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(auditLogPort.hasEvent(AuditEventType.PASSKEY_REVOKED))
        }

    // -------------------------------------------------------------------------
    // Scope enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `scope enforcement without USERS_READ gets 403 on GET`() =
        testApplication {
            application { installTestApp() }
            val writeOnlyKey = apiKeyWithScopes(listOf(ApiScope.USERS_WRITE))

            val response = client.get("/t/acme/api/v1/users/10/passkeys") { bearerAuth(writeOnlyKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `scope enforcement without USERS_WRITE gets 403 on DELETE`() =
        testApplication {
            application { installTestApp() }
            val readOnlyKey = apiKeyWithScopes(listOf(ApiScope.USERS_READ))
            val credential = seedCredential(UserId(10))

            val response =
                client.delete("/t/acme/api/v1/passkeys/${credential.id}") { bearerAuth(readOnlyKey) }

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
                webAuthnService = webAuthnService,
                webAuthnCredentialRepository = credentialRepo,
            )
        }
    }
}
