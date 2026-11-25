package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
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
import com.kauth.fakes.FakeWebhookSender
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for `GET /users` pagination (v1.21.0) and the
 * `UserDto` fields added alongside it: `requiredActions`, `isLocked`,
 * `createdAt`.
 */
class ApiUserRoutesTest {
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
    private val consents = com.kauth.fakes.FakeConsentRepository()

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
            collisionCheck =
                com.kauth.domain.service
                    .IdentifierCollisionCheck(userRepo),
            usernameGenerator =
                com.kauth.domain.service
                    .UsernameGenerator(userRepo),
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

        tenantRepo.add(tenant)

        rawApiKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Test Key",
                    scopes = listOf(ApiScope.USERS_READ),
                ) as ApiKeyResult.Success
            ).value.rawKey
        writeKey =
            (
                apiKeyService.create(
                    tenantId = TenantId(1),
                    name = "Write Key",
                    scopes = listOf(ApiScope.USERS_READ, ApiScope.USERS_WRITE),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun seedUsers(
        count: Int,
        prefix: String = "user",
    ): List<User> =
        (1..count).map { i ->
            userRepo.add(
                User(
                    tenantId = TenantId(1),
                    username = "$prefix$i",
                    email = "$prefix$i@acme.com",
                    fullName = "$prefix $i",
                    passwordHash = hasher.hash("pass"),
                ),
            )
        }

    // =========================================================================
    // GET /users — pagination
    // =========================================================================

    private var writeKey: String = ""

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createUser(body: String) =
        client.post("/t/acme/api/v1/users") {
            bearerAuth(writeKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun subjectOf(response: io.ktor.client.statement.HttpResponse) =
        kotlinx.coroutines.runBlocking {
            jsonCodec
                .parseToJsonElement(response.bodyAsText())
                .jsonObject["subject"]!!
                .jsonPrimitive.content
        }

    @Test
    fun `a created user's response carries the sub its tokens will have, a UUID by default`() =
        testApplication {
            application { installTestApp() }

            val response =
                createUser(
                    """{"username":"newbie","email":"newbie@acme.com","fullName":"New","password":"Correct-Horse-9!"}""",
                )

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val subject = subjectOf(response)
            assertTrue(Regex("^[0-9a-f-]{36}$").matches(subject), subject)
            assertEquals(subject, userRepo.findByUsername(TenantId(1), "newbie")?.subject)
        }

    @Test
    fun `a user's consents are listed and one can be revoked`() =
        testApplication {
            application { installTestApp() }
            val user = seedUsers(1).single()
            val app =
                appRepo.add(
                    com.kauth.domain.model.Application(
                        id =
                            com.kauth.domain.model
                                .ApplicationId(0),
                        tenantId = TenantId(1),
                        clientId = "partner-app",
                        name = "Partner Reports",
                        description = null,
                        accessType = com.kauth.domain.model.AccessType.PUBLIC,
                        enabled = true,
                        consentRequired = true,
                    ),
                )
            consents.save(TenantId(1), user.id!!, app.id, setOf("openid", "email"), java.time.Instant.now())

            val listed =
                client.get("/t/acme/api/v1/users/${user.id!!.value}/consents") { bearerAuth(rawApiKey) }
            assertEquals(HttpStatusCode.OK, listed.status)
            val first =
                jsonCodec
                    .parseToJsonElement(listed.bodyAsText())
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("partner-app", first["clientId"]!!.jsonPrimitive.content)
            assertEquals(listOf("email", "openid"), first["scopes"]!!.jsonArray.map { it.jsonPrimitive.content })

            val readOnly =
                client.delete("/t/acme/api/v1/users/${user.id!!.value}/consents/${app.id.value}") {
                    bearerAuth(rawApiKey)
                }
            assertEquals(HttpStatusCode.Forbidden, readOnly.status)

            val revoked =
                client.delete("/t/acme/api/v1/users/${user.id!!.value}/consents/${app.id.value}") {
                    bearerAuth(writeKey)
                }
            assertEquals(HttpStatusCode.NoContent, revoked.status)
            assertNull(consents.find(TenantId(1), user.id!!, app.id))

            val again =
                client.delete("/t/acme/api/v1/users/${user.id!!.value}/consents/${app.id.value}") {
                    bearerAuth(writeKey)
                }
            assertEquals(HttpStatusCode.NotFound, again.status)
        }

    @Test
    fun `a caller can choose the sub, and a second user can't take it`() =
        testApplication {
            application { installTestApp() }

            val first =
                createUser(
                    """{"username":"seeded","email":"seeded@acme.com","fullName":"S","password":"Correct-Horse-9!","subject":"seed-user-1"}""",
                )
            val clash =
                createUser(
                    """{"username":"other","email":"other@acme.com","fullName":"O","password":"Correct-Horse-9!","subject":"seed-user-1"}""",
                )
            val padded =
                createUser(
                    """{"username":"third","email":"third@acme.com","fullName":"T","password":"Correct-Horse-9!","subject":" x "}""",
                )

            assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
            assertEquals("seed-user-1", subjectOf(first))
            assertEquals(HttpStatusCode.Conflict, clash.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, padded.status)
            assertEquals(null, userRepo.findByUsername(TenantId(1), "other"))
        }

    @Test
    fun `GET users by subject finds exactly that user, or nobody`() =
        testApplication {
            application { installTestApp() }
            seedUsers(3)
            createUser(
                """{"username":"known","email":"known@acme.com","fullName":"K","password":"Correct-Horse-9!","subject":"rp-key-7"}""",
            )

            val hit = client.get("/t/acme/api/v1/users?subject=rp-key-7") { bearerAuth(rawApiKey) }
            val miss = client.get("/t/acme/api/v1/users?subject=rp-key") { bearerAuth(rawApiKey) }

            val data = jsonCodec.parseToJsonElement(hit.bodyAsText()).jsonObject["data"]!!.jsonArray
            assertEquals(1, data.size)
            assertEquals("known", data[0].jsonObject["username"]!!.jsonPrimitive.content)
            assertEquals(
                0,
                jsonCodec
                    .parseToJsonElement(miss.bodyAsText())
                    .jsonObject["data"]!!
                    .jsonArray.size,
            )
        }

    @Test
    fun `GET users returns first page with default limit when no params supplied`() =
        testApplication {
            application { installTestApp() }
            seedUsers(10)

            val response =
                client.get("/t/acme/api/v1/users") {
                    bearerAuth(rawApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            val meta = body["meta"]!!.jsonObject
            assertEquals(10, data.size)
            assertEquals(10, meta["total"]!!.jsonPrimitive.int)
            assertEquals(0, meta["offset"]!!.jsonPrimitive.int)
            assertEquals(50, meta["limit"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET users respects limit param`() =
        testApplication {
            application { installTestApp() }
            seedUsers(15)

            val response =
                client.get("/t/acme/api/v1/users?limit=5") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            val meta = body["meta"]!!.jsonObject
            assertEquals(5, data.size)
            assertEquals(5, meta["limit"]!!.jsonPrimitive.int)
            assertEquals(15, meta["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET users respects offset param`() =
        testApplication {
            application { installTestApp() }
            seedUsers(5, prefix = "seq")

            val response =
                client.get("/t/acme/api/v1/users?limit=2&offset=2") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            assertEquals(2, data.size)
            val usernames = data.map { it.jsonObject["username"]!!.jsonPrimitive.content }
            assertEquals(listOf("seq3", "seq4"), usernames)
        }

    @Test
    fun `GET users caps limit at 200`() =
        testApplication {
            application { installTestApp() }
            seedUsers(3)

            val response =
                client.get("/t/acme/api/v1/users?limit=999") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val meta = body["meta"]!!.jsonObject
            assertEquals(200, meta["limit"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET users coerces invalid limit to default`() =
        testApplication {
            application { installTestApp() }
            seedUsers(3)

            val response =
                client.get("/t/acme/api/v1/users?limit=abc") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val meta = body["meta"]!!.jsonObject
            assertEquals(50, meta["limit"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET users search filters and pagination combined`() =
        testApplication {
            application { installTestApp() }
            seedUsers(7, prefix = "other")
            seedUsers(3, prefix = "match")

            val response =
                client.get("/t/acme/api/v1/users?search=match&limit=3") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = body["data"]!!.jsonArray
            val meta = body["meta"]!!.jsonObject
            assertEquals(3, data.size)
            assertEquals(3, meta["total"]!!.jsonPrimitive.int)
            assertTrue(
                data.all {
                    it.jsonObject["username"]!!
                        .jsonPrimitive.content
                        .startsWith("match")
                },
            )
        }

    // =========================================================================
    // UserDto field completeness — GET /users/{id}
    // =========================================================================

    @Test
    fun `UserDto includes requiredActions in response`() =
        testApplication {
            application { installTestApp() }
            val user =
                userRepo.add(
                    User(
                        tenantId = TenantId(1),
                        username = "pending",
                        email = "pending@acme.com",
                        fullName = "Pending User",
                        passwordHash = hasher.hash("pass"),
                        requiredActions = setOf(RequiredAction.CHANGE_PASSWORD),
                    ),
                )

            val response =
                client.get("/t/acme/api/v1/users/${user.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val requiredActions = body["requiredActions"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("CHANGE_PASSWORD" in requiredActions)
        }

    @Test
    fun `UserDto isLocked is true when lockedUntil is in future`() =
        testApplication {
            application { installTestApp() }
            val user =
                userRepo.add(
                    User(
                        tenantId = TenantId(1),
                        username = "locked",
                        email = "locked@acme.com",
                        fullName = "Locked User",
                        passwordHash = hasher.hash("pass"),
                        lockedUntil = Instant.now().plusSeconds(3600),
                    ),
                )

            val response =
                client.get("/t/acme/api/v1/users/${user.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, body["isLocked"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `UserDto isLocked is false when lockedUntil is in past`() =
        testApplication {
            application { installTestApp() }
            val user =
                userRepo.add(
                    User(
                        tenantId = TenantId(1),
                        username = "unlocked",
                        email = "unlocked@acme.com",
                        fullName = "Unlocked User",
                        passwordHash = hasher.hash("pass"),
                        lockedUntil = Instant.now().minusSeconds(3600),
                    ),
                )

            val response =
                client.get("/t/acme/api/v1/users/${user.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(false, body["isLocked"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `UserDto createdAt is ISO-8601 when non-null`() =
        testApplication {
            application { installTestApp() }
            val createdAt = Instant.parse("2026-01-01T00:00:00Z")
            val user =
                userRepo.add(
                    User(
                        tenantId = TenantId(1),
                        username = "dated",
                        email = "dated@acme.com",
                        fullName = "Dated User",
                        passwordHash = hasher.hash("pass"),
                        createdAt = createdAt,
                    ),
                )

            val response =
                client.get("/t/acme/api/v1/users/${user.id!!.value}") {
                    bearerAuth(rawApiKey)
                }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(isoFormatter.format(createdAt), body["createdAt"]!!.jsonPrimitive.content)
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
                consentService =
                    com.kauth.domain.service
                        .ConsentService(consents, appRepo, sessionRepo, userRepo, auditLogPort),
            )
        }
    }
}
