package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the v1.21.0 user state management endpoints:
 *   - POST   /users/{id}/enable
 *   - DELETE /users/{id}/mfa/reset
 *   - POST   /users/{id}/revoke-sessions
 *   - GET    /users/{id}/sessions
 */
class ApiUserStateTest {
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
                    scopes = listOf(ApiScope.USERS_WRITE, ApiScope.SESSIONS_READ, ApiScope.SESSIONS_WRITE),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun seedUser(
        username: String = "alice",
        enabled: Boolean = true,
        mfaEnabled: Boolean = false,
    ): User =
        userRepo.add(
            User(
                tenantId = TenantId(1),
                username = username,
                email = "$username@acme.com",
                fullName = username,
                passwordHash = hasher.hash("pass"),
                enabled = enabled,
                mfaEnabled = mfaEnabled,
            ),
        )

    private fun seedSession(
        userId: UserId,
        revoked: Boolean = false,
    ): Session =
        sessionRepo.save(
            Session(
                tenantId = TenantId(1),
                userId = userId,
                clientId = null,
                scopes = "openid",
                accessTokenHash = "hash-${System.nanoTime()}",
                refreshTokenHash = null,
                ipAddress = "127.0.0.1",
                createdAt = Instant.now(),
                expiresAt = Instant.now().plusSeconds(3600),
                revokedAt = if (revoked) Instant.now() else null,
            ),
        )

    private fun apiKeyMissingScope(scopes: List<String>): String =
        (
            apiKeyService.create(
                tenantId = TenantId(1),
                name = "Limited Key",
                scopes = scopes,
            ) as ApiKeyResult.Success
        ).value.rawKey

    // =========================================================================
    // POST /users/{id}/enable
    // =========================================================================

    @Test
    fun `POST users enable returns 204 and flips enabled flag on a disabled user`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser(enabled = false)
            val userId = user.id!!

            val response =
                client.post("/t/acme/api/v1/users/${userId.value}/enable") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val updated = userRepo.findById(userId, TenantId(1))
            assertEquals(true, updated?.enabled)
        }

    @Test
    fun `POST users enable is idempotent for already-enabled user`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser(enabled = true)
            val userId = user.id!!

            val response =
                client.post("/t/acme/api/v1/users/${userId.value}/enable") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val updated = userRepo.findById(userId, TenantId(1))
            assertEquals(true, updated?.enabled)
        }

    @Test
    fun `POST users enable returns 404 for unknown user`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/api/v1/users/99999/enable") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // =========================================================================
    // DELETE /users/{id}/mfa/reset
    // =========================================================================

    @Test
    fun `DELETE users mfa reset returns 204 and clears mfaEnabled + enrollments`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser(mfaEnabled = true)
            val userId = user.id!!
            mfaRepo.saveEnrollment(
                MfaEnrollment(
                    userId = userId,
                    tenantId = TenantId(1),
                    secret = "SECRET",
                    verified = true,
                ),
            )

            val response =
                client.delete("/t/acme/api/v1/users/${userId.value}/mfa/reset") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val updated = userRepo.findById(userId, TenantId(1))
            assertEquals(false, updated?.mfaEnabled)
            assertEquals(null, mfaRepo.findEnrollmentByUserId(userId, "TOTP"))
        }

    @Test
    fun `DELETE users mfa reset is idempotent when user has no MFA enrolled`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser(mfaEnabled = false)

            val response =
                client.delete("/t/acme/api/v1/users/${user.id!!.value}/mfa/reset") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE users mfa reset returns 404 for user in a different tenant — MFA enrollments preserved`() =
        testApplication {
            application { installTestApp() }
            val tenantB =
                Tenant(
                    id = TenantId(2),
                    slug = "globex",
                    displayName = "Globex",
                    issuerUrl = null,
                    theme = TenantTheme.DEFAULT,
                )
            tenantRepo.add(tenantB)
            val otherTenantUser =
                userRepo.add(
                    User(
                        tenantId = TenantId(2),
                        username = "carol",
                        email = "carol@globex.com",
                        fullName = "carol",
                        passwordHash = hasher.hash("pass"),
                        enabled = true,
                        mfaEnabled = true,
                    ),
                )
            val otherTenantUserId = otherTenantUser.id!!
            val savedEnrollment =
                mfaRepo.saveEnrollment(
                    MfaEnrollment(
                        userId = otherTenantUserId,
                        tenantId = TenantId(2),
                        secret = "SECRET",
                        verified = true,
                    ),
                )

            // rawApiKey belongs to tenant A ("acme") — attempt to reset MFA for a tenant B user.
            val response =
                client.delete("/t/acme/api/v1/users/${otherTenantUserId.value}/mfa/reset") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(savedEnrollment, mfaRepo.findEnrollmentByUserId(otherTenantUserId, "TOTP"))
            assertEquals(true, userRepo.findById(otherTenantUserId, TenantId(2))?.mfaEnabled)
            assertEquals(false, auditLogPort.hasEvent(com.kauth.domain.model.AuditEventType.MFA_DISABLED))
        }

    // =========================================================================
    // POST /users/{id}/revoke-sessions
    // =========================================================================

    @Test
    fun `POST users revoke-sessions returns count and revokes all active sessions for user`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser()
            val userId = user.id!!
            seedSession(userId)
            seedSession(userId)
            seedSession(userId, revoked = true)

            val response =
                client.post("/t/acme/api/v1/users/${userId.value}/revoke-sessions") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["revoked"]!!.jsonPrimitive.int)
            assertTrue(sessionRepo.findActiveByUser(TenantId(1), userId).isEmpty())
        }

    @Test
    fun `POST users revoke-sessions returns revoked 0 for user with no sessions`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser()

            val response =
                client.post("/t/acme/api/v1/users/${user.id!!.value}/revoke-sessions") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["revoked"]!!.jsonPrimitive.int)
        }

    @Test
    fun `POST users revoke-sessions does not touch other users' sessions in same tenant`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser("alice")
            val other = seedUser("bob")
            val userId = user.id!!
            val otherId = other.id!!
            seedSession(userId)
            seedSession(otherId)

            val response =
                client.post("/t/acme/api/v1/users/${userId.value}/revoke-sessions") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, sessionRepo.findActiveByUser(TenantId(1), otherId).size)
        }

    // =========================================================================
    // GET /users/{id}/sessions
    // =========================================================================

    @Test
    fun `GET users sessions returns active sessions filtered to that user`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser("alice")
            val other = seedUser("bob")
            val userId = user.id!!
            seedSession(userId)
            seedSession(userId)
            seedSession(other.id!!)

            val response =
                client.get("/t/acme/api/v1/users/${userId.value}/sessions") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(2, data.size)
            assertTrue(data.all { it.jsonObject["userId"]!!.jsonPrimitive.int == userId.value })
        }

    @Test
    fun `GET users sessions returns empty data array when user has no sessions`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser()

            val response =
                client.get("/t/acme/api/v1/users/${user.id!!.value}/sessions") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["data"]!!.jsonArray.size)
            assertEquals(0, body["meta"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        }

    // =========================================================================
    // Scope enforcement
    // =========================================================================

    @Test
    fun `scope enforcement key without USERS_WRITE gets 403 on POST enable`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser(enabled = false)
            val limitedKey = apiKeyMissingScope(listOf(ApiScope.SESSIONS_READ, ApiScope.SESSIONS_WRITE))

            val response =
                client.post("/t/acme/api/v1/users/${user.id!!.value}/enable") {
                    bearerAuth(limitedKey)
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `scope enforcement key without SESSIONS_WRITE gets 403 on POST revoke-sessions`() =
        testApplication {
            application { installTestApp() }
            val user = seedUser()
            val limitedKey = apiKeyMissingScope(listOf(ApiScope.USERS_WRITE, ApiScope.SESSIONS_READ))

            val response =
                client.post("/t/acme/api/v1/users/${user.id!!.value}/revoke-sessions") {
                    bearerAuth(limitedKey)
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
