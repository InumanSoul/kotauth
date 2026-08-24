package com.kauth.adapter.web.scim

import com.kauth.adapter.web.api.AlwaysAllowLimiter
import com.kauth.adapter.web.api.apiRoutes
import com.kauth.adapter.web.api.stubEmailOtpService
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
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
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for `/Users`, mounted at `/t/{tenantSlug}/scim/v2/Users`.
 */
class ScimUserRoutesTest {
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

    // A real boundary, not a pass-through: the combined profile-write-plus-toggle in
    // applyScimWrite exists to be rolled back. That throw is unreachable through HTTP today (the
    // toggle fails only for a user the profile write just proved exists), so the boundary itself is
    // asserted in FakeTransactionRunnerRollbackTest rather than staged here.
    private val transactionRunner = FakeTransactionRunner(userRepo)

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

    // SMTP-ready, unlike acme/globex — exercises the invite-email branch createUser's
    // sendInvite=true path only takes when a tenant can actually send mail.
    private val smtpTenant =
        Tenant(
            id = TenantId(3),
            slug = "initech",
            displayName = "Initech",
            issuerUrl = "https://initech.kotauth.dev",
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(),
            smtpEnabled = true,
            smtpHost = "smtp.initech.example",
            smtpFromAddress = "noreply@initech.example",
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

    private val claimMapperService = CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private val webhookService =
        WebhookService(
            endpointRepository = FakeWebhookEndpointRepository(),
            deliveryRepository = FakeWebhookDeliveryRepository(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private var scimKey: String = ""
    private var smtpTenantScimKey: String = ""
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
        tenantRepo.add(smtpTenant)

        scimKey =
            (
                apiKeyService.create(
                    tenantId = acme.id,
                    name = "Provisioning Key",
                    scopes = listOf(ApiScope.SCIM),
                ) as ApiKeyResult.Success
            ).value.rawKey

        smtpTenantScimKey =
            (
                apiKeyService.create(
                    tenantId = smtpTenant.id,
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

    private fun addUser(
        username: String,
        externalId: String? = null,
        givenName: String? = null,
        familyName: String? = null,
        tenantId: TenantId = acme.id,
    ): User =
        userRepo.add(
            User(
                tenantId = tenantId,
                username = username,
                email = "$username@example.com",
                fullName = "$givenName $familyName".trim().ifBlank { username },
                passwordHash = User.SENTINEL_PASSWORD_HASH,
                externalId = externalId,
                givenName = givenName,
                familyName = familyName,
                enabled = true,
            ),
        )

    private fun usersUrl(filter: String): String =
        "/t/acme/scim/v2/Users?filter=" + java.net.URLEncoder.encode(filter, "UTF-8")

    private fun createBody(
        userName: String,
        externalId: String? = null,
        password: String? = null,
    ) = buildString {
        append(
            """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"$userName",""" +
                """"emails":[{"value":"$userName@example.com","type":"work"}]""",
        )
        externalId?.let { append(""","externalId":"$it"""") }
        password?.let { append(""","password":"$it"""") }
        append("}")
    }

    // -------------------------------------------------------------------------
    // GET /Users — listing, filtering, pagination
    // -------------------------------------------------------------------------

    @Test
    fun `startIndex is 1-based - startIndex 1 returns the first user, not the second`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")
            addUser("carol")

            val response =
                client.get("/t/acme/scim/v2/Users?startIndex=1&count=1") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val resources = body["Resources"]!!.jsonArray
            assertEquals(1, resources.size)
            assertEquals("alice", resources[0].jsonObject["userName"]!!.jsonPrimitive.content)
        }

    @Test
    fun `count=0 returns totalResults with an empty Resources array`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")

            val response = client.get("/t/acme/scim/v2/Users?count=0") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(0, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `count above the advertised cap is clamped to 200`() =
        testApplication {
            application { installTestApp() }
            repeat(201) { addUser("user%03d".format(it)) }

            val response = client.get("/t/acme/scim/v2/Users?count=5000") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(201, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(200, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `startIndex 2 skips the first user`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")
            addUser("carol")

            val response = client.get("/t/acme/scim/v2/Users?startIndex=2&count=1") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val resources = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject["Resources"]!!.jsonArray
            assertEquals(1, resources.size)
            assertEquals("bob", resources[0].jsonObject["userName"]!!.jsonPrimitive.content)
        }

    @Test
    fun `startIndex beyond the total returns an empty page with the true totalResults`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")

            val response = client.get("/t/acme/scim/v2/Users?startIndex=50") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["Resources"]!!.jsonArray.size)
            assertEquals(2, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(50, body["startIndex"]!!.jsonPrimitive.int)
        }

    @Test
    fun `startIndex 0 is coerced to 1`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")

            val response = client.get("/t/acme/scim/v2/Users?startIndex=0&count=1") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["startIndex"]!!.jsonPrimitive.int)
            assertEquals(
                "alice",
                body["Resources"]!!
                    .jsonArray[0]
                    .jsonObject["userName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `a filter matching no user returns an empty Resources array with totalResults 0`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")

            val response = client.get(usersUrl("""userName eq "ghost"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(0, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `filter by userName returns only the matching user`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")

            val response =
                client.get(usersUrl("""userName eq "alice"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val resources = body["Resources"]!!.jsonArray
            assertEquals(1, resources.size)
            assertEquals("alice", resources[0].jsonObject["userName"]!!.jsonPrimitive.content)
        }

    @Test
    fun `filter by externalId returns only the matching user`() =
        testApplication {
            application { installTestApp() }
            addUser("alice", externalId = "idp-1")
            addUser("bob", externalId = "idp-2")

            val response =
                client.get(usersUrl("""externalId eq "idp-2"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val resources = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject["Resources"]!!.jsonArray
            assertEquals(1, resources.size)
            assertEquals("bob", resources[0].jsonObject["userName"]!!.jsonPrimitive.content)
        }

    @Test
    fun `filter by id returns only the matching user`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            addUser("bob")

            val response =
                client.get(usersUrl("""id eq "${alice.id!!.value}"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val resources = body["Resources"]!!.jsonArray
            assertEquals(1, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(1, resources.size)
            assertEquals("alice", resources[0].jsonObject["userName"]!!.jsonPrimitive.content)
        }

    @Test
    fun `filter by id with no match returns an empty Resources array with totalResults 0`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")

            val response = client.get(usersUrl("""id eq "999999"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(0, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `filter by id never returns a user from another tenant`() =
        testApplication {
            application { installTestApp() }
            val foreignUser = addUser("eve", tenantId = globex.id)

            val response =
                client.get(usersUrl("""id eq "${foreignUser.id!!.value}"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(0, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `startIndex 2 against a single fast-path match yields an empty page with totalResults 1`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")

            val response =
                client.get(usersUrl("""userName eq "alice"""") + "&startIndex=2") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(0, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `a filtered request with count=0 returns the true totalResults with an empty Resources array`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")

            val response = client.get(usersUrl("""userName eq "alice"""") + "&count=0") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(0, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `a compound filter matches correctly across multiple bounded scan chunks`() =
        testApplication {
            application { installTestApp() }
            // Chunk size is 500; 650 users guarantees a match in each of two chunks.
            repeat(650) { addUser("user%04d".format(it)) }

            val response =
                client.get(usersUrl("""userName eq "user0010" or userName eq "user0600"""")) {
                    bearerAuth(scimKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["totalResults"]!!.jsonPrimitive.int)
            val names =
                body["Resources"]!!
                    .jsonArray
                    .map { it.jsonObject["userName"]!!.jsonPrimitive.content }
                    .toSet()
            assertEquals(setOf("user0010", "user0600"), names)
        }

    @Test
    fun `totalResults reflects the true match count across chunks even when count is smaller`() =
        testApplication {
            application { installTestApp() }
            repeat(650) { addUser("user%04d".format(it)) }

            val response =
                client.get(usersUrl("""userName eq "user0010" or userName eq "user0600"""") + "&count=1") {
                    bearerAuth(scimKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(1, body["Resources"]!!.jsonArray.size)
        }

    @Test
    fun `groups are loaded once for the returned page, not once per match - pins the N+1 fix`() =
        testApplication {
            application { installTestApp() }
            repeat(20) { addUser("user%04d".format(it)) }

            // "active" has no indexed lookup, so this exercises the bounded-scan path with
            // every one of the 20 users matching.
            val response = client.get(usersUrl("""active eq true""") + "&count=3") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(20, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(3, body["Resources"]!!.jsonArray.size)
            // A single batched call sized to the page (3), never one per match (20).
            assertEquals(listOf(3), groupRepo.findGroupsForUsersCallSizes)
        }

    @Test
    fun `an unsupported filter returns 400 invalidFilter, never an unfiltered list`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")
            addUser("bob")

            val response =
                client.get(usersUrl("""nope eq "x"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidFilter", body["scimType"]?.jsonPrimitive?.content)
        }

    // -------------------------------------------------------------------------
    // POST /Users — create
    // -------------------------------------------------------------------------

    @Test
    fun `POST creates a user and the response never contains a password`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("newuser", password = "correct-horse-battery"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("newuser", body["userName"]!!.jsonPrimitive.content)
            assertNull(body["password"])
        }

    @Test
    fun `a supplied password is hashed through the tenant's password policy, not written raw`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("newuser", password = "correct-horse-battery"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val stored = userRepo.findByUsername(acme.id, "newuser")
            assertEquals("hashed:correct-horse-battery", stored?.passwordHash)
        }

    @Test
    fun `a password that violates the tenant's policy is rejected, proving the policy path runs`() =
        testApplication {
            application { installTestApp() }

            // Tenant minimum is 8 (SecurityConfig default); this is 3.
            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("newuser", password = "abc"))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertNull(userRepo.findByUsername(acme.id, "newuser"))
        }

    @Test
    fun `POST without a password creates an unset-password account rather than failing`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("invited"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val stored = userRepo.findByUsername(acme.id, "invited")
            assertEquals(User.SENTINEL_PASSWORD_HASH, stored?.passwordHash)
        }

    @Test
    fun `POST response carries a Location header pointing at the new user`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("newuser"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val id = body["id"]!!.jsonPrimitive.content
            val location = response.headers["Location"]
            assertEquals(true, location != null && location.endsWith("/t/acme/scim/v2/Users/$id"))
            // meta.location must match the Location header, not just also exist.
            assertEquals(
                location,
                body["meta"]
                    ?.jsonObject
                    ?.get("location")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "User",
                body["meta"]
                    ?.jsonObject
                    ?.get("resourceType")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `GET Users by id includes a meta location matching the resource's own URL`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")

            val response = client.get("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val location =
                body["meta"]
                    ?.jsonObject
                    ?.get("location")
                    ?.jsonPrimitive
                    ?.content
            assertEquals(true, location != null && location.endsWith("/t/acme/scim/v2/Users/${user.id!!.value}"))
        }

    @Test
    fun `POST with active false creates a deactivated user`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"disableduser",""" +
                            """"emails":[{"value":"disableduser@example.com","type":"work"}],"active":false}""",
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(false, body["active"]!!.jsonPrimitive.boolean)
            val stored = userRepo.findByUsername(acme.id, "disableduser")
            assertEquals(false, stored?.enabled)
        }

    @Test
    fun `a passwordless create on an SMTP-ready tenant sends an invite with an absolute link`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/initech/scim/v2/Users") {
                    bearerAuth(smtpTenantScimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("provisioned"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, emailPort.sent.size)
            val inviteUrl = emailPort.sent.single().url
            // A bare relative path ("/t/initech/accept-invite?...") means baseUrl was never
            // resolved and passed through — the link would 404 in any real browser.
            assertEquals(true, inviteUrl.startsWith("http://") || inviteUrl.startsWith("https://"))
            assertEquals(true, inviteUrl.contains("/t/initech/accept-invite?token="))
        }

    @Test
    fun `a duplicate userName returns 409 with scimType uniqueness`() =
        testApplication {
            application { installTestApp() }
            addUser("alice")

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("alice"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("uniqueness", body["scimType"]?.jsonPrimitive?.content)
        }

    // -------------------------------------------------------------------------
    // GET /Users/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `GET fetches a user by id`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")

            val response = client.get("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("alice", body["userName"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET on an unknown id returns 404`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/Users/999999") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `a user in another workspace is 404, never 403`() =
        testApplication {
            application { installTestApp() }
            val foreignUser = addUser("eve", tenantId = globex.id)

            val response = client.get("/t/acme/scim/v2/Users/${foreignUser.id!!.value}") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET includes the user's actual group memberships, not an empty placeholder`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")
            val engineering =
                groupRepo.add(
                    com.kauth.domain.model
                        .Group(tenantId = acme.id, name = "Engineering"),
                )
            groupRepo.addUserToGroup(user.id!!, engineering.id!!)

            val response = client.get("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val groups = body["groups"]!!.jsonArray
            assertEquals(1, groups.size)
            assertEquals("Engineering", groups[0].jsonObject["display"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET omits groups for a user with no memberships`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")

            val response = client.get("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(scimKey) }

            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNull(body["groups"])
        }

    // -------------------------------------------------------------------------
    // PUT /Users/{id} — full replace
    // -------------------------------------------------------------------------

    @Test
    fun `PUT clears an omitted attribute, in both the response and the stored user`() =
        testApplication {
            application { installTestApp() }
            val putUser = addUser("puttarget", givenName = "Ada", familyName = "Lovelace")

            val putResponse =
                client.put("/t/acme/scim/v2/Users/${putUser.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"puttarget"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, putResponse.status)
            val putBody = jsonCodec.parseToJsonElement(putResponse.bodyAsText()).jsonObject
            assertNull(putBody["name"])
            val stored = userRepo.findById(putUser.id!!, acme.id)
            assertNull(stored?.givenName)
            assertNull(stored?.familyName)
        }

    @Test
    fun `PATCH leaves an unmentioned attribute alone, in both the response and the stored user`() =
        testApplication {
            application { installTestApp() }
            val patchUser = addUser("patchtarget", givenName = "Grace", familyName = "Hopper")

            val patchResponse =
                client.patch("/t/acme/scim/v2/Users/${patchUser.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":true}]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, patchResponse.status)
            val patchBody = jsonCodec.parseToJsonElement(patchResponse.bodyAsText()).jsonObject
            assertEquals("Grace", patchBody["name"]!!.jsonObject["givenName"]!!.jsonPrimitive.content)
            assertEquals("Hopper", patchBody["name"]!!.jsonObject["familyName"]!!.jsonPrimitive.content)
            val stored = userRepo.findById(patchUser.id!!, acme.id)
            assertEquals("Grace", stored?.givenName)
            assertEquals("Hopper", stored?.familyName)
        }

    @Test
    fun `PATCH pathless add of one name sub-attribute preserves the other`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("adalovelace", givenName = "Ada", familyName = "Lovelace")

            val patchResponse =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],""" +
                            """"Operations":[{"op":"add","value":{"name":{"givenName":"Ada B."}}}]}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, patchResponse.status)

            val getResponse =
                client.get("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(scimKey) }
            val body = jsonCodec.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertEquals("Ada B.", body["name"]!!.jsonObject["givenName"]!!.jsonPrimitive.content)
            assertEquals("Lovelace", body["name"]!!.jsonObject["familyName"]!!.jsonPrimitive.content)
            val stored = userRepo.findById(user.id!!, acme.id)
            assertEquals("Lovelace", stored?.familyName)
        }

    @Test
    fun `PUT on another workspace's user is 404`() =
        testApplication {
            application { installTestApp() }
            val foreignUser = addUser("eve", tenantId = globex.id)

            val response =
                client.put("/t/acme/scim/v2/Users/${foreignUser.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"eve"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // -------------------------------------------------------------------------
    // PATCH /Users/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `PATCH pathless add of emails stores the new address`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[""" +
                            """{"op":"add","value":{"emails":[""" +
                            """{"value":"new@corp.example","type":"work","primary":true}]}}]}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("new@corp.example", userRepo.findById(user.id!!, acme.id)?.email)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "new@corp.example",
                body["emails"]!!
                    .jsonArray
                    .single()
                    .jsonObject["value"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `PATCH targeted add of emails stores the new address`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[""" +
                            """{"op":"add","path":"emails","value":[""" +
                            """{"value":"targeted@corp.example","type":"work"}]}]}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("targeted@corp.example", userRepo.findById(user.id!!, acme.id)?.email)
        }

    @Test
    fun `PATCH with a scalar over the complex name is rejected and both parts survive`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada", givenName = "Ada", familyName = "Lovelace")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[""" +
                            """{"op":"add","value":{"name":"Ada Lovelace"}}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]?.jsonPrimitive?.content)
            val stored = userRepo.findById(user.id!!, acme.id)!!
            assertEquals("Ada", stored.givenName)
            assertEquals("Lovelace", stored.familyName)
        }

    @Test
    fun `PATCH with a targeted scalar over the complex name is rejected`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada", givenName = "Ada", familyName = "Lovelace")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[""" +
                            """{"op":"replace","path":"name","value":"Ada Lovelace"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val stored = userRepo.findById(user.id!!, acme.id)!!
            assertEquals("Ada", stored.givenName)
            assertEquals("Lovelace", stored.familyName)
        }

    @Test
    fun `PUT with a scalar over the complex name is rejected and both parts survive`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada", givenName = "Ada", familyName = "Lovelace")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ada",""" +
                            """"name":"Ada Lovelace","emails":[{"value":"ada@example.com","type":"work"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]?.jsonPrimitive?.content)
            val stored = userRepo.findById(user.id!!, acme.id)!!
            assertEquals("Ada", stored.givenName)
            assertEquals("Lovelace", stored.familyName)
        }

    @Test
    fun `PUT omitting name entirely still clears both parts`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada", givenName = "Ada", familyName = "Lovelace")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ada",""" +
                            """"emails":[{"value":"ada@example.com","type":"work"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val stored = userRepo.findById(user.id!!, acme.id)!!
            assertEquals(null, stored.givenName)
            assertEquals(null, stored.familyName)
        }

    @Test
    fun `PUT renaming userName is rejected with mutability, not silently dropped`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("original")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"renamed",""" +
                            """"emails":[{"value":"original@example.com","type":"work"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("mutability", body["scimType"]?.jsonPrimitive?.content)
            assertEquals("original", userRepo.findById(user.id!!, acme.id)?.username)
        }

    @Test
    fun `PUT with a password on an existing user is rejected and the hash is untouched`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")
            val hashBefore = userRepo.findById(user.id!!, acme.id)?.passwordHash

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"alice",""" +
                            """"emails":[{"value":"alice@example.com","type":"work"}],"password":"new-password-1"}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]?.jsonPrimitive?.content)
            assertEquals(hashBefore, userRepo.findById(user.id!!, acme.id)?.passwordHash)
            // The rejection must point the integrator somewhere, not just say no.
            assertTrue(body["detail"]!!.jsonPrimitive.content.contains("admin API"))
        }

    @Test
    fun `PATCH replace on active deactivates the user`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":false}]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(false, body["active"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `PATCH on an unknown attribute returns 400 invalidPath`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"nope","value":"x"}]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidPath", body["scimType"]?.jsonPrimitive?.content)
        }

    @Test
    fun `PATCH preserves attributes the operations never mention`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice", externalId = "idp-1", givenName = "Ada", familyName = "Lovelace")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"displayName","value":"Ada L."}]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("idp-1", body["externalId"]!!.jsonPrimitive.content)
            assertEquals("Ada", body["name"]!!.jsonObject["givenName"]!!.jsonPrimitive.content)
            assertEquals("Lovelace", body["name"]!!.jsonObject["familyName"]!!.jsonPrimitive.content)
            assertEquals("Ada L.", body["displayName"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PATCH renaming userName is rejected with mutability, not silently dropped`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("original")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"userName","value":"renamed"}]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("mutability", body["scimType"]?.jsonPrimitive?.content)
            assertEquals("original", userRepo.findById(user.id!!, acme.id)?.username)
        }

    @Test
    fun `PATCH with a password on an existing user is rejected and the hash is untouched`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")
            val hashBefore = userRepo.findById(user.id!!, acme.id)?.passwordHash

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"password","value":"new-password-1"}]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]?.jsonPrimitive?.content)
            assertEquals(hashBefore, userRepo.findById(user.id!!, acme.id)?.passwordHash)
        }

    @Test
    fun `PATCH on another workspace's user is 404`() =
        testApplication {
            application { installTestApp() }
            val foreignUser = addUser("eve", tenantId = globex.id)

            val response =
                client.patch("/t/acme/scim/v2/Users/${foreignUser.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":false}]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // -------------------------------------------------------------------------
    // DELETE /Users/{id} — deactivate, not delete
    // -------------------------------------------------------------------------

    @Test
    fun `DELETE deactivates rather than deleting - the user stays fetchable with active false`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")

            val deleteResponse = client.delete("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val getResponse = client.get("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val body = jsonCodec.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertEquals(false, body["active"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `DELETE on another workspace's user is 404`() =
        testApplication {
            application { installTestApp() }
            val foreignUser = addUser("eve", tenantId = globex.id)

            val response = client.delete("/t/acme/scim/v2/Users/${foreignUser.id!!.value}") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // -------------------------------------------------------------------------
    // Scope enforcement — one route-scoped plugin guards every /Users handler
    // (see ScimScopePlugin), rather than a hand-written check per handler.
    // -------------------------------------------------------------------------

    @Test
    fun `GET Users without the scim scope gets 403 in the SCIM error envelope`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/scim/v2/Users") { bearerAuth(noScimKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "urn:ietf:params:scim:api:messages:2.0:Error",
                body["schemas"]!!.jsonArray[0].jsonPrimitive.content,
            )
        }

    @Test
    fun `POST Users without the scim scope gets 403 and creates nothing`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(noScimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("blocked"))
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(userRepo.findByUsername(acme.id, "blocked"))
        }

    @Test
    fun `DELETE Users without the scim scope gets 403 and leaves the user untouched`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("alice")

            val response = client.delete("/t/acme/scim/v2/Users/${user.id!!.value}") { bearerAuth(noScimKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(true, userRepo.findById(user.id!!, acme.id)?.enabled)
        }

    // -------------------------------------------------------------------------
    // Test wiring
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Attribute shapes — PUT, POST and PATCH all inherit the one table
    // -------------------------------------------------------------------------

    private fun scimTypeOf(body: String) =
        jsonCodec
            .parseToJsonElement(body)
            .jsonObject["scimType"]!!
            .jsonPrimitive.content

    @Test
    fun `PATCH setting active to the string false is rejected, not a silent failed deprovision`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],""" +
                            """"Operations":[{"op":"replace","path":"active","value":"false"}]}""",
                    )
                }

            // A 200 here would tell the identity provider the account is deprovisioned while it
            // keeps authenticating. The account is still enabled either way — the 400 is what
            // stops the provider from believing otherwise.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals(true, userRepo.findById(user.id, acme.id)!!.enabled)
        }

    @Test
    fun `PUT with a bare-string emails value is rejected, the same answer Groups gives that shape`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],""" +
                            """"userName":"ada","displayName":"Renamed",""" +
                            """"emails":"ada@new.example"}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            // displayName carries the guard: asserting only the unchanged address would pass even
            // against the pre-fix behaviour, which fell back to that same stored address. A
            // rejected request must leave the name it also carried untouched.
            val stored = userRepo.findById(user.id, acme.id)!!
            assertEquals("ada@example.com", stored.email)
            assertEquals(user.fullName, stored.fullName)
        }

    @Test
    fun `PUT with an array of bare-string emails is rejected rather than ignored`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],""" +
                            """"userName":"ada","emails":["ada@new.example"]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("ada@example.com", userRepo.findById(user.id, acme.id)!!.email)
        }

    @Test
    fun `PUT with a numeric externalId is rejected instead of erasing the stored key`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada", externalId = "usr-1")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],""" +
                            """"userName":"ada","externalId":9182}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("usr-1", userRepo.findById(user.id, acme.id)!!.externalId)
        }

    @Test
    fun `PUT with a numeric givenName is rejected instead of erasing the stored one`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada", givenName = "Ada", familyName = "Lovelace")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ada",""" +
                            """"name":{"givenName":123,"familyName":"Lovelace"}}""",
                    )
                }

            // `name` is an object, so the top-level shape check passed and the null cast wrote
            // givenName = null over "Ada" under a 200.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("Ada", userRepo.findById(user.id, acme.id)!!.givenName)
        }

    @Test
    fun `PUT with a numeric emails value is rejected instead of echoing the stored address back`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada")

            val response =
                client.put("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ada",""" +
                            """"emails":[{"value":123,"type":"work"}]}""",
                    )
                }

            // The byte-equivalent shape on /Groups (`{"members":[{"value":123}]}`) has always been
            // a 400; /Users answered 200 with the old address echoed back.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("ada@example.com", userRepo.findById(user.id, acme.id)!!.email)
        }

    @Test
    fun `PATCH replacing a filtered emails value with a number is rejected, not a 200 no-op`() =
        testApplication {
            application { installTestApp() }
            val user = addUser("ada")

            val response =
                client.patch("/t/acme/scim/v2/Users/${user.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":""" +
                            """[{"op":"replace","path":"emails[type eq \"work\"].value","value":123}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("ada@example.com", userRepo.findById(user.id, acme.id)!!.email)
        }

    @Test
    fun `POST with an RFC 7643 attribute Kotauth does not store creates the user and ignores it`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ada",""" +
                            """"emails":[{"value":"ada@x.example","type":"work"}],"title":"Engineer"}""",
                    )
                }

            // A 400 here reads as permanent to a provisioning client, which drops the record —
            // over an attribute that is valid SCIM and simply not persisted.
            assertEquals(HttpStatusCode.Created, response.status)
            assertNull(jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject["title"])
            assertEquals("ada", userRepo.findByUsername(acme.id, "ada")!!.username)
        }

    @Test
    fun `POST carrying a schema extension object creates the user rather than dropping the record`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User",""" +
                            """"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"],"userName":"ada",""" +
                            """"emails":[{"value":"ada@x.example","type":"work"}],""" +
                            """"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User":{"department":"R&D"}}""",
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("ada", userRepo.findByUsername(acme.id, "ada")!!.username)
        }

    @Test
    fun `POST with a misspelled attribute is still rejected rather than dropping it`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Users") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ada",""" +
                            """"emailz":[{"value":"ada@x.example","type":"work"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidSyntax", scimTypeOf(response.bodyAsText()))
            assertNull(userRepo.findByUsername(acme.id, "ada"))
        }

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
                transactionRunner = transactionRunner,
            )
        }
    }
}
