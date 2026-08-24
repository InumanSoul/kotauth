package com.kauth.adapter.web.scim

import com.kauth.adapter.web.api.AlwaysAllowLimiter
import com.kauth.adapter.web.api.apiRoutes
import com.kauth.adapter.web.api.stubEmailOtpService
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for `/Groups`, mounted at `/t/{tenantSlug}/scim/v2/Groups`.
 */
class ScimGroupRoutesTest {
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

    // groupRepo is registered so the write transactions in ScimGroupRoutes actually roll back:
    // a rejected member has to take the metadata write with it, and nothing else asserts that.
    private val transactionRunner = FakeTransactionRunner(groupRepo)

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

    private val accountService =
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
    private var noScimKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        groupRepo.clear()
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

    private fun addUser(
        username: String,
        tenantId: TenantId = acme.id,
    ): User =
        userRepo.add(
            User(
                tenantId = tenantId,
                username = username,
                email = "$username@example.com",
                fullName = username,
                passwordHash = User.SENTINEL_PASSWORD_HASH,
                enabled = true,
            ),
        )

    private fun addGroup(
        name: String,
        tenantId: TenantId = acme.id,
        externalId: String? = null,
        roleIds: List<RoleId> = emptyList(),
        members: List<User> = emptyList(),
        parentGroupId: GroupId? = null,
    ): Group {
        val group =
            groupRepo.add(
                Group(
                    tenantId = tenantId,
                    name = name,
                    externalId = externalId,
                    roleIds = roleIds,
                    parentGroupId = parentGroupId,
                ),
            )
        members.forEach { groupRepo.addUserToGroup(it.id!!, group.id!!) }
        return group
    }

    private fun groupsUrl(
        filter: String? = null,
        tenantSlug: String = "acme",
    ): String {
        val base = "/t/$tenantSlug/scim/v2/Groups"
        return if (filter == null) base else base + "?filter=" + java.net.URLEncoder.encode(filter, "UTF-8")
    }

    private fun groupUrl(
        id: Int,
        tenantSlug: String = "acme",
    ): String = "/t/$tenantSlug/scim/v2/Groups/$id"

    private fun createBody(
        displayName: String,
        externalId: String? = null,
        memberIds: List<Int> = emptyList(),
    ): String {
        val members = memberIds.joinToString(",") { """{"value":"$it","type":"User"}""" }
        val externalIdPart = externalId?.let { ""","externalId":"$it"""" } ?: ""
        return """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"$displayName"""" +
            """$externalIdPart,"members":[$members]}"""
    }

    private fun patchBody(vararg ops: String) =
        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[${ops.joinToString(",")}]}"""

    private fun membersArrayValue(vararg ids: Int) = "[" + ids.joinToString(",") { """{"value":"$it"}""" } + "]"

    private fun scimTypeOf(body: String) =
        jsonCodec
            .parseToJsonElement(body)
            .jsonObject["scimType"]!!
            .jsonPrimitive.content

    @Test
    fun `PATCH remove of a padded member id actually removes the member`() =
        testApplication {
            application { installTestApp() }
            val member = addUser("ada")
            val group = addGroup("Engineering", members = listOf(member))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[""" +
                            """{"op":"remove","path":"members","value":[""" +
                            """{"value":" ${member.id!!.value} "}]}]}""",
                    )
                }

            // A 200 that left the member in place is a deprovisioning miss reported as success.
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(emptyList(), groupRepo.findUserIdsInGroup(group.id!!))
        }

    private fun keyWithDialect(dialect: String): String =
        (
            apiKeyService.create(
                tenantId = acme.id,
                name = "Provisioning key ($dialect)",
                scopes = listOf(ApiScope.SCIM),
                scimDialect = dialect,
            ) as ApiKeyResult.Success
        ).value.rawKey

    // -------------------------------------------------------------------------
    // Dialect wiring — /Groups threads the stored dialect through its own three call
    // sites, so it needs its own guard. Each test sends a payload only the non-default
    // dialect accepts, then the same payload on an `rfc` key.
    // -------------------------------------------------------------------------

    /**
     * A member entry whose advisory `display` is not a string: the Okta dialect strips the
     * sub-attribute before the shape check ever sees it, where `rfc` rejects the entry.
     */
    private fun memberWithWronglyTypedDisplay(id: Int) = """[{"value":"$id","display":42}]"""

    @Test
    fun `POST strips a wrongly-typed member display on an Okta-dialect key, and rejects it on an rfc key`() =
        testApplication {
            application { installTestApp() }
            val member = addUser("ada")

            fun body(name: String) =
                """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"$name",""" +
                    """"members":${memberWithWronglyTypedDisplay(member.id!!.value)}}"""

            val accepted =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(keyWithDialect(OktaDialect.id))
                    contentType(ContentType.Application.Json)
                    setBody(body("Engineering"))
                }

            assertEquals(HttpStatusCode.Created, accepted.status)
            val created = groupRepo.findByName(acme.id, "Engineering", null)!!
            assertEquals(listOf(member.id), groupRepo.findUserIdsInGroup(created.id!!))

            val rejected =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(body("Strict"))
                }

            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertEquals(null, groupRepo.findByName(acme.id, "Strict", null))
        }

    @Test
    fun `PUT strips a wrongly-typed member display on an Okta-dialect key, and rejects it on an rfc key`() =
        testApplication {
            application { installTestApp() }
            val member = addUser("ada")
            val lenient = addGroup("Engineering")
            val strict = addGroup("Support")

            fun body(name: String) =
                """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"$name",""" +
                    """"members":${memberWithWronglyTypedDisplay(member.id!!.value)}}"""

            val accepted =
                client.put(groupUrl(lenient.id!!.value)) {
                    bearerAuth(keyWithDialect(OktaDialect.id))
                    contentType(ContentType.Application.Json)
                    setBody(body("Engineering"))
                }

            assertEquals(HttpStatusCode.OK, accepted.status)
            assertEquals(listOf(member.id), groupRepo.findUserIdsInGroup(lenient.id!!))

            val rejected =
                client.put(groupUrl(strict.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(body("Support"))
                }

            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertEquals(emptyList(), groupRepo.findUserIdsInGroup(strict.id!!))
        }

    @Test
    fun `PATCH strips a wrongly-typed member display on an Okta-dialect key, and rejects it on an rfc key`() =
        testApplication {
            application { installTestApp() }
            val member = addUser("ada")
            val lenient = addGroup("Engineering")
            val strict = addGroup("Support")
            val body =
                """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[""" +
                    """{"op":"add","path":"members","value":${memberWithWronglyTypedDisplay(member.id!!.value)}}]}"""

            val accepted =
                client.patch(groupUrl(lenient.id!!.value)) {
                    bearerAuth(keyWithDialect(OktaDialect.id))
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.OK, accepted.status)
            assertEquals(listOf(member.id), groupRepo.findUserIdsInGroup(lenient.id!!))

            val rejected =
                client.patch(groupUrl(strict.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertEquals(emptyList(), groupRepo.findUserIdsInGroup(strict.id!!))
        }

    // -------------------------------------------------------------------------
    // POST / GET / filtering
    // -------------------------------------------------------------------------

    @Test
    fun `POST creates a group and returns 201 with a Location header`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", externalId = "grp-1"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(
                true,
                response.headers["Location"]?.endsWith(
                    "/Groups/${groupRepo.findByExternalId(acme.id, "grp-1")!!.id!!.value}",
                ),
            )
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Engineering", body["displayName"]!!.jsonPrimitive.content)
            assertEquals("grp-1", body["externalId"]!!.jsonPrimitive.content)
            assertEquals(0, body["members"]!!.jsonArray.size)
        }

    @Test
    fun `meta location is present and agrees with the POST Location header`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val locationHeader = response.headers["Location"]
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val metaLocation =
                body["meta"]!!
                    .jsonObject["location"]!!
                    .jsonPrimitive.content
            assertEquals(locationHeader, metaLocation)
        }

    @Test
    fun `POST with a member includes it in the created resource`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", memberIds = listOf(alice.id!!.value)))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                listOf(alice.id!!.value.toString()),
                body["members"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content },
            )
        }

    @Test
    fun `POST with a member from another workspace is rejected`() =
        testApplication {
            application { installTestApp() }
            val foreign = addUser("mallory", tenantId = globex.id)

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", memberIds = listOf(foreign.id!!.value)))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
            // The route saves the group and only then reconciles membership, so the 400 is only
            // honest if the save went away with it. Without a rolling-back runner this fails.
            assertEquals(0L, groupRepo.countByTenantId(acme.id))
        }

    @Test
    fun `POST rejects a duplicate displayName with a uniqueness conflict`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `POST rejects a nested group member with 400 invalidValue`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Eng",""" +
                            """"members":[{"value":"1","type":"Group"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects a nested group member regardless of casing`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Eng",""" +
                            """"members":[{"value":"1","type":"group"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a SCIM key without the scim scope is rejected`() =
        testApplication {
            application { installTestApp() }

            val response = client.get(groupsUrl()) { bearerAuth(noScimKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET Groups filters by externalId via the indexed fast path`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering", externalId = "grp-1")
            addGroup("Sales", externalId = "grp-2")

            val response = client.get(groupsUrl("""externalId eq "grp-2"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(
                "Sales",
                body["Resources"]!!
                    .jsonArray[0]
                    .jsonObject["displayName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `GET Groups filters by displayName via the bounded scan`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")
            addGroup("Sales")

            val response = client.get(groupsUrl("""displayName eq "Engineering"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(
                "Engineering",
                body["Resources"]!!
                    .jsonArray[0]
                    .jsonObject["displayName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `GET Groups with no filter returns every group in the tenant, scoped to it`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")
            addGroup("Sales")
            addGroup("Other Workspace Group", tenantId = globex.id)

            val response = client.get(groupsUrl()) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["totalResults"]!!.jsonPrimitive.int)
            // Asserting only totalResults would still pass if another workspace's group leaked
            // into Resources — check the actual identities returned, not just the count.
            val names =
                body["Resources"]!!.jsonArray.map { it.jsonObject["displayName"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("Engineering", "Sales"), names)
        }

    @Test
    fun `GET Groups honors startIndex and count for pagination`() =
        testApplication {
            application { installTestApp() }
            addGroup("Alpha")
            addGroup("Beta")
            addGroup("Gamma")

            val response =
                client.get(groupsUrl() + "?startIndex=2&count=1") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(3, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(2, body["startIndex"]!!.jsonPrimitive.int)
            assertEquals(1, body["Resources"]!!.jsonArray.size)
            // Groups are ordered by name: Alpha, Beta, Gamma — startIndex=2 lands on Beta.
            assertEquals(
                "Beta",
                body["Resources"]!!
                    .jsonArray[0]
                    .jsonObject["displayName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `GET Groups with count=0 returns the total and no resources`() =
        testApplication {
            application { installTestApp() }
            addGroup("Alpha")
            addGroup("Beta")
            addGroup("Other Workspace Group", tenantId = globex.id)

            // A connector probes for the tenant's size before it pages; count=0 must answer with
            // the total and send nothing back (RFC 7644 3.4.2.4).
            val response = client.get(groupsUrl() + "?count=0") { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["totalResults"]!!.jsonPrimitive.int)
            assertEquals(0, body["Resources"]!!.jsonArray.size)
            assertEquals(0, body["itemsPerPage"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET Groups loads membership for the page in one batch, not once per group`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val bob = addUser("bob")
            addGroup("Engineering", members = listOf(alice))
            addGroup("Sales", members = listOf(bob))
            groupRepo.findUserIdsForGroupsCallSizes.clear()

            val response = client.get(groupsUrl()) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(2), groupRepo.findUserIdsForGroupsCallSizes)
        }

    // -------------------------------------------------------------------------
    // GET /Groups/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `GET Groups id includes members`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val bob = addUser("bob")
            val group = addGroup("Engineering", members = listOf(alice, bob))

            val response = client.get(groupUrl(group.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            val values = body["members"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf(alice.id!!.value.toString(), bob.id!!.value.toString()), values)
        }

    @Test
    fun `a group in another workspace is 404, never 403`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response = client.get(groupUrl(foreignGroup.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT on another workspace's group is 404`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response =
                client.put(groupUrl(foreignGroup.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Renamed"))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PATCH on another workspace's group is 404`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response =
                client.patch(groupUrl(foreignGroup.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"remove","path":"members"}"""))
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `DELETE on another workspace's group is 404 and leaves it intact`() =
        testApplication {
            application { installTestApp() }
            val foreignGroup = addGroup("Foreign", tenantId = globex.id)

            val response = client.delete(groupUrl(foreignGroup.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(foreignGroup, groupRepo.findById(foreignGroup.id))
        }

    // -------------------------------------------------------------------------
    // PUT — full replace
    // -------------------------------------------------------------------------

    @Test
    fun `PUT never writes role assignments, in either direction`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val group = addGroup("Engineering", roleIds = listOf(RoleId(42)), members = listOf(alice))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", memberIds = listOf(alice.id!!.value)))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // No `update` implementation writes group_roles, so "the roles survived" cannot fail
            // for the reason it sounds like. What this route actually has to guarantee is that it
            // never issues a grant of its own — a PUT that clears absent attributes must not be
            // able to touch permissions at all.
            assertEquals(emptyList(), groupRepo.assignRoleToGroupCalls)
            assertEquals(listOf(RoleId(42)), groupRepo.findById(group.id)!!.roleIds)
        }

    @Test
    fun `PUT omitting members clears the group, matching full-replace semantics`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val group = addGroup("Engineering", members = listOf(alice))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Engineering"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PUT with a member from another workspace is rejected`() =
        testApplication {
            application { installTestApp() }
            val foreign = addUser("mallory", tenantId = globex.id)
            val group = addGroup("Engineering")

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering", memberIds = listOf(foreign.id!!.value)))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PUT with a nested type Group member is rejected`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering")

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Engineering",""" +
                            """"members":[{"value":"1","type":"Group"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PUT omitting externalId clears it`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering", externalId = "grp-1")

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Engineering"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(groupRepo.findById(group.id)!!.externalId)
        }

    // -------------------------------------------------------------------------
    // PATCH — the member matrix. Every assertion is on the COMPLETE resulting
    // membership, never just on the member that changed.
    // -------------------------------------------------------------------------

    @Test
    fun `PATCH add members appends to the existing collection`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":${membersArrayValue(u3.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                setOf(u1.id!!, u2.id!!, u3.id!!),
                groupRepo.findUserIdsInGroup(group.id).toSet(),
            )
        }

    @Test
    fun `PATCH add with a bare unwrapped member object appends, not replaces`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":{"value":"${u3.id!!.value}"}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                setOf(u1.id!!, u2.id!!, u3.id!!),
                groupRepo.findUserIdsInGroup(group.id).toSet(),
            )
        }

    @Test
    fun `PATCH a pathless add of members appends and every existing member survives`() =
        testApplication {
            application { installTestApp() }
            // Deliberately many members: replacing instead of appending would leave the group
            // with just the one member the connector sent, losing every role it granted.
            val existingMembers = (1..400).map { addUser("u$it") }
            val group = addGroup("Engineering", members = existingMembers)
            val newMember = addUser("newHire")

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","value":{"members":${membersArrayValue(newMember.id!!.value)}}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val expected = (existingMembers.map { it.id!! } + newMember.id!!).toSet()
            assertEquals(expected, groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH replace with plain-string member values is rejected and membership is unchanged`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody("""{"op":"replace","path":"members","value":["7","8"]}"""),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
            assertEquals(setOf(u1.id!!, u2.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH remove members filter removes exactly the matched member`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members[value eq \"${u2.id!!.value}\"]"}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                setOf(u1.id!!, u3.id!!),
                groupRepo.findUserIdsInGroup(group.id).toSet(),
            )
        }

    @Test
    fun `PATCH remove members with no filter empties the group`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"remove","path":"members"}"""))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH remove members with a one-entry value removes only that member`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members","value":${membersArrayValue(u2.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(setOf(u1.id!!, u3.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH remove members with a two-entry value removes exactly those two`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members","value":""" +
                                "${membersArrayValue(u1.id!!.value, u3.id!!.value)}}",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(setOf(u2.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH remove members with a value naming the last member leaves an empty collection`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members","value":${membersArrayValue(u1.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(0, body["members"]!!.jsonArray.size)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH remove members with a value naming a non-member leaves the membership untouched`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val outsider = addUser("outsider")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members","value":${membersArrayValue(outsider.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(setOf(u1.id!!, u2.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH remove members with a bare-string value is rejected instead of emptying the group`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody("""{"op":"remove","path":"members","value":"${u2.id!!.value}"}"""),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals(setOf(u1.id!!, u2.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH replace members replaces the whole collection`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u9 = addUser("u9")
            val group = addGroup("Engineering", members = listOf(u1, u2))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"replace","path":"members","value":${membersArrayValue(u9.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(setOf(u9.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH a remove that empties the group followed by an add in the same request lands as a proper collection`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val u2 = addUser("u2")
            val u3 = addUser("u3")
            val newMember = addUser("newMember")
            val group = addGroup("Engineering", members = listOf(u1, u2, u3))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"remove","path":"members"}""",
                            """{"op":"add","path":"members","value":${membersArrayValue(newMember.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                listOf(newMember.id!!.value.toString()),
                body["members"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content },
            )
            assertEquals(setOf(newMember.id), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH rejects a nested group member with 400 invalidValue`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering")

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":[{"value":"1","type":"GROUP"}]}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalidValue", body["scimType"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PATCH a member from another workspace is rejected and does not partially apply`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val foreign = addUser("mallory", tenantId = globex.id)
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"add","path":"members","value":${membersArrayValue(foreign.id!!.value)}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(setOf(u1.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH replacing members with a bare string is rejected and the group keeps its members`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"replace","path":"members","value":"${u1.id!!.value}"}"""))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals(setOf(u1.id), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH with a pathless members object is rejected and the group keeps its members`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"replace","value":{"members":{"value":"${u1.id!!.value}","type":"User"}}}""",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals(setOf(u1.id), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PUT with a members object instead of an array is rejected and membership survives`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"displayName":"Eng","members":{"value":"${u1.id!!.value}"}}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals(setOf(u1.id), groupRepo.findUserIdsInGroup(group.id).toSet())
            assertEquals("Engineering", groupRepo.findById(group.id)!!.name)
        }

    @Test
    fun `PUT omitting members entirely still clears membership`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Eng"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(emptySet(), groupRepo.findUserIdsInGroup(group.id!!).toSet())
        }

    @Test
    fun `a bad members entry is named by index and shape, never echoed back`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))
            val huge = "x".repeat(4096)

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Eng",""" +
                            """"members":[{"value":"${u1.id!!.value}"},"$huge"]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val detail =
                jsonCodec
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["detail"]!!
                    .jsonPrimitive.content
            assertEquals(true, detail.contains("members[1]"), detail)
            assertEquals(true, detail.contains("a string"), detail)
            assertEquals(false, detail.contains(huge))
            assertEquals(true, detail.length < 200, "detail must stay bounded, was ${detail.length}")
        }

    @Test
    fun `PATCH can rename displayName without touching membership`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"replace","path":"displayName","value":"Engineering Team"}"""))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Engineering Team", groupRepo.findById(group.id)!!.name)
            assertEquals(setOf(u1.id!!), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `GET Groups lists without loading roles`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering", roleIds = listOf(RoleId(42)))

            val response = client.get(groupsUrl()) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf(false), groupRepo.findByTenantIdLoadRolesFlags)
            // The group really does have a role; the list path just never pays to load it.
            assertEquals(listOf(RoleId(42)), groupRepo.findById(group.id!!)!!.roleIds)
        }

    @Test
    fun `filter scanning Groups does not load roles either`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering", roleIds = listOf(RoleId(42)))

            val response = client.get(groupsUrl("""displayName eq "Engineering"""")) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(true, groupRepo.findByTenantIdLoadRolesFlags.isNotEmpty())
            assertEquals(false, groupRepo.findByTenantIdLoadRolesFlags.any { it })
        }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------

    @Test
    fun `DELETE removes the group but leaves member users intact`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val group = addGroup("Engineering", members = listOf(alice))

            val deleteResponse = client.delete(groupUrl(group.id!!.value)) { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val getGroupResponse = client.get(groupUrl(group.id.value)) { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.NotFound, getGroupResponse.status)

            val getUserResponse = client.get("/t/acme/scim/v2/Users/${alice.id!!.value}") { bearerAuth(scimKey) }
            assertEquals(HttpStatusCode.OK, getUserResponse.status)
        }

    @Test
    fun `DELETE on a group with children is refused before any delete reaches the repository`() =
        testApplication {
            application { installTestApp() }
            val parent = addGroup("Engineering")
            val child = addGroup("Backend", parentGroupId = parent.id)

            val response = client.delete(groupUrl(parent.id!!.value)) { bearerAuth(scimKey) }

            assertEquals(HttpStatusCode.Conflict, response.status)
            // The service refuses before calling delete at all; asserting the child survives
            // would pass against a cascade the fake does not model, so assert the call itself.
            assertEquals(emptyList(), groupRepo.deleteCalls)
            assertNotNull(groupRepo.findById(parent.id))
            assertNotNull(groupRepo.findById(child.id!!))
            val body = jsonCodec.parseToJsonElement(response.bodyAsText()).jsonObject
            // No scimType, which RFC 7644 §3.12 permits. `uniqueness` would tell the client the
            // value is already taken, and its remediation — retry with a different displayName —
            // can never clear a subgroup block; the detail names the subgroups instead.
            assertNull(body["scimType"])
            assertEquals(true, body["detail"]!!.jsonPrimitive.content.contains("Backend"))
        }

    @Test
    fun `DELETE succeeds once the child group has been reparented away`() =
        testApplication {
            application { installTestApp() }
            val parent = addGroup("Engineering")
            val child = addGroup("Backend", parentGroupId = parent.id)

            assertEquals(
                HttpStatusCode.Conflict,
                client.delete(groupUrl(parent.id!!.value)) { bearerAuth(scimKey) }.status,
            )
            groupRepo.update(child.copy(parentGroupId = null))

            assertEquals(
                HttpStatusCode.NoContent,
                client.delete(groupUrl(parent.id.value)) { bearerAuth(scimKey) }.status,
            )
            assertNull(groupRepo.findById(parent.id))
            assertNotNull(groupRepo.findById(child.id!!))
        }

    @Test
    fun `a duplicate displayName still reports uniqueness, so the two conflicts stay distinguishable`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Engineering"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("uniqueness", scimTypeOf(response.bodyAsText()))
        }

    @Test
    fun `PUT with a singular member typo is rejected instead of silently emptying the group`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"displayName":"Eng","member":[{"value":"${u1.id!!.value}"}]}""",
                    )
                }

            // The same typo in a PATCH path has always been a 400; a PUT used to answer 200 with
            // the group emptied, because the misspelled attribute was dropped and `members` read
            // as absent, which PUT treats as "clear".
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidSyntax", scimTypeOf(response.bodyAsText()))
            assertEquals(setOf(u1.id), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `POST with an unknown attribute is rejected rather than dropping it`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"displayName":"Engineering","nickName":"Eng"}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidSyntax", scimTypeOf(response.bodyAsText()))
            assertEquals(0L, groupRepo.countByTenantId(acme.id))
        }

    @Test
    fun `POST with a mis-cased attribute name is accepted, not rejected as unknown`() =
        testApplication {
            application { installTestApp() }

            // RFC 7643 2.1 makes attribute names case-insensitive. Exact-match lookups turned a
            // mis-cased name into an unknown attribute, and a 400 makes a client drop the record.
            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"DISPLAYNAME":"Engineering","externalid":"grp-7"}""",
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val stored = groupRepo.findByTenantId(acme.id).single()
            assertEquals("Engineering", stored.name)
            assertEquals("grp-7", stored.externalId)
        }

    @Test
    fun `PATCH with a mis-cased path targets the same attribute`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering")

            val response =
                client.patch("/t/acme/scim/v2/Groups/${group.id!!.value}") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],""" +
                            """"Operations":[{"op":"replace","path":"displayname","value":"Platform"}]}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Platform", groupRepo.findById(group.id!!)!!.name)
        }

    @Test
    fun `POST with a User attribute is rejected on a Group, not accepted and ignored`() =
        testApplication {
            application { installTestApp() }

            // `nickName` is valid RFC 7643 — on a User. One shared unstored-attribute list let it
            // ride along here, which is the cross-resource-type leak the filter scope already fixed.
            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"displayName":"Engineering","nickName":"Eng"}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidSyntax", scimTypeOf(response.bodyAsText()))
            assertEquals(0L, groupRepo.countByTenantId(acme.id))
        }

    @Test
    fun `POST carrying a schema extension object creates the group rather than dropping the record`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"displayName":"Engineering",""" +
                            """"urn:ietf:params:scim:schemas:extension:kauth:2.0:Group":{"costCenter":"42"}}""",
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1L, groupRepo.countByTenantId(acme.id))
        }

    @Test
    fun `GET Groups filtering on a User attribute is invalidFilter, not an empty result set`() =
        testApplication {
            application { installTestApp() }
            addGroup("Engineering")

            val response = client.get(groupsUrl("""userName eq "ada"""")) { bearerAuth(scimKey) }

            // 200 with totalResults 0 reads as "no such group", and a provisioning client acts
            // on that by creating a duplicate.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidFilter", scimTypeOf(response.bodyAsText()))
        }

    // -------------------------------------------------------------------------
    // Transaction boundary — a rejected member takes the metadata write with it
    // -------------------------------------------------------------------------

    @Test
    fun `PUT that renames the group and supplies a bad member rolls the rename back`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val foreign = addUser("mallory", tenantId = globex.id)
            val group = addGroup("Engineering", members = listOf(alice))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(createBody("Platform", memberIds = listOf(foreign.id!!.value)))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("Engineering", groupRepo.findById(group.id)!!.name)
            assertEquals(setOf(alice.id), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    @Test
    fun `PATCH that renames the group and supplies a bad member rolls the rename back`() =
        testApplication {
            application { installTestApp() }
            val alice = addUser("alice")
            val foreign = addUser("mallory", tenantId = globex.id)
            val group = addGroup("Engineering", members = listOf(alice))

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        patchBody(
                            """{"op":"replace","path":"displayName","value":"Platform"}""",
                            """{"op":"replace","path":"members","value":""" +
                                membersArrayValue(foreign.id!!.value) + "}",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("Engineering", groupRepo.findById(group.id)!!.name)
            assertEquals(setOf(alice.id), groupRepo.findUserIdsInGroup(group.id).toSet())
        }

    // -------------------------------------------------------------------------
    // Attribute shapes — PUT, POST and PATCH all inherit the one table
    // -------------------------------------------------------------------------

    @Test
    fun `PATCH replacing externalId with a number is rejected and the stored key survives`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering", externalId = "grp-1")

            val response =
                client.patch(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(patchBody("""{"op":"replace","path":"externalId","value":9182}"""))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            // The whole point: the correlation key is not NULLed out under a 200, which is what
            // sent the next sync looking for a group it could no longer find.
            assertEquals("grp-1", groupRepo.findById(group.id)!!.externalId)
        }

    @Test
    fun `PUT with an array displayName is rejected instead of echoing the stored name back`() =
        testApplication {
            application { installTestApp() }
            val group = addGroup("Engineering")

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"displayName":["Engineering"],"members":[]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals("Engineering", groupRepo.findById(group.id)!!.name)
        }

    @Test
    fun `POST with a numeric externalId is rejected instead of creating an uncorrelated group`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.post("/t/acme/scim/v2/Groups") {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],""" +
                            """"displayName":"Engineering","externalId":9182}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals(0L, groupRepo.countByTenantId(acme.id))
        }

    @Test
    fun `PUT with a numeric member type is rejected rather than admitting an unguarded member`() =
        testApplication {
            application { installTestApp() }
            val u1 = addUser("u1")
            val group = addGroup("Engineering", members = listOf(u1))

            val response =
                client.put(groupUrl(group.id!!.value)) {
                    bearerAuth(scimKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"displayName":"Eng",""" +
                            """"members":[{"value":"${u1.id!!.value}","type":7}]}""",
                    )
                }

            // `type` is what the nested-group guard reads; a number cast to null slips past it.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalidValue", scimTypeOf(response.bodyAsText()))
            assertEquals(setOf(u1.id), groupRepo.findUserIdsInGroup(group.id).toSet())
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
                accountService = accountService,
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
                transactionRunner = transactionRunner,
                userRepository = userRepo,
            )
        }
    }
}
